# Cycle de vie d'une session et de ses exécutions

Cette évolution remplace la proposition de la PR #8, fermée sans fusion.
Elle sépare la conversation, une demande d'exécution et le thread utilisé pour
l'exécuter. Elle ne dépend d'aucun framework.

## API et responsabilités

- `AgentBuilder` configure et construit une session `AgentLoop`. C'est un builder
  mutable, pas une fabrique concurrente. Une application peut exposer sa propre
  fabrique qui crée un builder et une mémoire pour chaque conversation.
- `AgentLoop` possède le contexte d'une conversation et son dispatcher d'événements.
  Une seule exécution est admise à la fois, y compris lorsqu'elle attend dans
  la file d'un executor. Des sessions indépendantes peuvent fonctionner en parallèle.
- `AgentRun` identifie une demande asynchrone (`UUID`), expose son état, son
  annulation et `CompletionStage<AgentRunResult> completion()`.
- `AgentRunResult` est le résultat terminal immuable, également retourné par
  `run(String)`. Aucun abonné n'est nécessaire pour obtenir la réponse.

```java
try (AgentLoop session = AgentBuilder.builder()
        .client(client)
        .memoryManager(conversationMemory)
        .build()) {
    AgentRunResult result = session.run("Ma question");
    if (result.status() == AgentRunState.SUCCEEDED) {
        String answer = result.finalResponse();
        // Utiliser la réponse directement.
    } else if (result.status() == AgentRunState.FAILED) {
        AgentRunResult.Failure failure = result.failure();
        // Diagnostic : phase, classe de l'erreur et message.
    }
}
```

## États et transitions d'une exécution

| État | Signification | Transitions possibles |
|---|---|---|
| `CREATED` | Session réservée, tâche en attente de son executor | `RUNNING`, `CANCELLED` |
| `RUNNING` | Exécution en cours | `CANCELLING`, `SUCCEEDED`, `FAILED`, `LIMIT_REACHED` |
| `CANCELLING` | Arrêt demandé, opération en cours potentiellement encore active | `CANCELLED`, `FAILED` |
| `SUCCEEDED` | Réponse finale enregistrée, ou outil terminal terminé avec son résultat enregistré | Aucune |
| `CANCELLED` | Arrêt constaté, plus aucune opération de cette exécution à engager | Aucune |
| `FAILED` | Exécution interrompue par une erreur | Aucune |
| `LIMIT_REACHED` | Budget d'étapes atteint sans réponse finale | Aucune |

Le résultat contient `runId`, `status`, `finalResponse`, `failure`,
`stepsExecuted` et `recoveryRequired`. Seul `SUCCEEDED` possède une réponse finale
non nulle, y compris lorsque celle-ci vient d'un outil terminal. Seul `FAILED`
possède un diagnostic. `stepsExecuted` compte les tentatives d'appel du modèle
pour la boucle ; les appels de compaction ne font pas partie de ce compteur.

Une instruction invalide, une session occupée/fermée/en récupération, l'absence
d'executor pour `submit`, ou un rejet par l'executor sont des refus d'admission :
ils lèvent une exception au demandeur sans ajouter de message en mémoire.
Un rejet normal de l'executor libère la réservation. Les erreurs après démarrage
produisent `FAILED`. Les erreurs JVM de type `Error` libèrent aussi le lifecycle
avec un résultat `FAILED` et une session en récupération, puis sont propagées au
thread d'exécution ; elles ne sont pas absorbées.

Une erreur ordinaire d'un outil reste un `ToolResult` d'échec fourni au modèle,
conformément au comportement existant. Elle ne rend pas forcément le run `FAILED`.

## Annulation et effets déjà engagés

`AgentRun.cancel()` retourne `true` uniquement pour la première demande acceptée.
Les appels suivants, notamment après terminaison, retournent `false`.
L'annulation et l'engagement du résultat terminal sont arbitrés sous le même verrou :
si l'annulation gagne, aucun résultat `SUCCEEDED` ne la remplace. Une erreur de
traitement ou de persistance conserve toutefois son diagnostic `FAILED`.

Une tâche encore `CREATED` devient immédiatement `CANCELLED`, sans attendre qu'un
worker soit disponible et sans modifier la conversation. Si l'executor exécute
ensuite cette tâche, elle ne fait rien. Pour une tâche démarrée, le contrôle se fait
aux frontières des opérations : instruction, contexte/compaction, modèle,
traitement de la réponse et admission de l'outil.

Une opération ayant franchi son contrôle d'admission peut finir après la demande
d'annulation. Les requêtes réseau, compactions et outils en cours ne sont pas
interrompus de force et leurs effets ne sont pas annulés. Un outil déjà exécuté a
son résultat enregistré avant la terminaison. Si l'appel d'outil a été enregistré
mais que l'annulation empêche son exécution, un résultat d'annulation correspondant
est enregistré pour ne pas laisser un appel sans résultat.

L'interruption du thread est un signal complémentaire : elle conduit à un résultat
`CANCELLED` aux mêmes frontières et le drapeau d'interruption est conservé.
`cancel()` n'interrompt jamais le worker. L'interruption d'un outil est conservée
par `ActionExecutor`, avec enregistrement de son résultat d'échec.

## Cohérence de la mémoire

Un échec du modèle ou une réponse invalide ne bloque pas automatiquement une session
restée cohérente. En revanche, une exception pendant une écriture de message ou
entre l'enregistrement d'un appel d'outil et celui de son résultat rend la session
`recoveryRequired`. Le contrôle est volontairement conservateur : même une écriture
ayant échoué sans effet peut nécessiter vérification.

Cette session refuse ensuite toute nouvelle demande. Il faut inspecter/réparer
les stockages et les éventuels effets externes, fermer cette session et en construire
une nouvelle avec une mémoire cohérente. Il n'existe pas de remise à zéro aveugle du
flag ni de réexécution automatique d'un outil dont l'effet pourrait avoir eu lieu.

Les invariants supposent que l'application ne modifie pas simultanément la mémoire
fournie à la session. Le lifecycle ne fournit pas de verrou distribué sur les fichiers,
ni d'isolation entre plusieurs instances partageant les mêmes repositories.

## Fermeture de la session

`OPEN -> CLOSING -> CLOSED` est irréversible.

- `closeAsync()` refuse les nouvelles demandes, demande l'annulation du run actif,
  puis attend sa terminaison et la livraison des événements acceptés. Il retourne
  un `CompletionStage<Void>` sans bloquer l'appelant. Il est idempotent.
- `close(Duration)` déclenche la même fermeture et attend au plus le délai indiqué.
  Il retourne `false` si ce délai expire. L'état reste `CLOSING` ; la fermeture
  continue quand les opérations et listeners finissent. L'attente est interruptible.
- `close()` attend sans limite et convient à `try-with-resources`. Depuis un outil
  de cette session ou un de ses listeners, il faut utiliser `closeAsync()` : les
  variantes bloquantes rejettent ces appels pour éviter d'attendre leur propre fin.

Un appel bloquant ou un listener qui ne finit jamais peut empêcher `CLOSED`.
Configurer les timeouts des clients/outils et utiliser une fermeture avec délai
lors de l'arrêt d'un serveur. Le thread virtuel interne de fermeture sert seulement
à drainer le dispatcher. La notification d'un run annulé avant démarrage utilise
aussi un thread virtuel pour que ses callbacks ne bloquent pas `cancel()` ou
`closeAsync()`. L'exécution de l'agent reste sur le caller ou son executor.

## Résultat et événements

`run()` et `AgentRun.completion()` n'attendent pas les listeners. Les événements
`TOOL_EXECUTION` et `AGENT_MESSAGE` restent disponibles et portent `runId`.
Un seul événement `RUN_COMPLETED` est publié par exécution admise et terminée ;
il porte le même objet `AgentRunResult` que la completion ou le retour synchrone.
Les refus d'admission ne produisent pas cet événement.

La notification terminale est mise en file avant l'admission d'un autre run.
Sa livraison peut précéder ou suivre le retour à l'appelant. Le résultat ne dépend
ni de l'existence d'abonnés ni de leur réussite. La fermeture draine les callbacks
acceptés. Un listener lent retarde les événements suivants et la fermeture, pas
le résultat. Ce mécanisme en mémoire ne garantit pas une livraison durable après
un crash du processus.

Le `CompletionStage` exposé est une vue protégée. Compléter ou annuler le
`CompletableFuture` obtenu par `toCompletableFuture()` n'altère pas le run : utiliser
`AgentRun.cancel()` pour demander l'arrêt. Les callbacks de completion peuvent
s'exécuter sur le thread qui complète le run ; déporter les traitements longs avec
les variantes `...Async` et un executor adapté. La fermeture attend la livraison
des `AgentEvent`, pas les traitements applicatifs attachés à la completion.

## Intégration dans une application Spring ou un autre serveur

L'application fournit un `Executor` (par exemple son `TaskExecutor`) et conserve
les handles par identifiant de demande. Elle doit choisir une politique de rejet
explicite quand la capacité est atteinte : un executor qui abandonne silencieusement
une tâche laisse celle-ci `CREATED` jusqu'à son annulation ou la fermeture.

```java
AgentLoop session = AgentBuilder.builder()
        .client(client)
        .memoryManager(conversationMemory)
        .executor(applicationExecutor)
        .build();

AgentRun run = session.submit("Ma question");
CompletionStage<AgentRunResult> response = run.completion();
// Un autre appel/contrôleur peut demander l'arrêt :
run.cancel();
// Fin de conversation ou arrêt du service :
CompletionStage<Void> shutdown = session.closeAsync();
```

Une session par conversation, pas un singleton de conversation partagé par tous
les utilisateurs. La fabrique de sessions et les dépendances réellement thread-safe
peuvent être partagées. Les stockages doivent être explicitement isolés : les chemins
par défaut du builder (`recall.json`, `memory.json`) ne constituent pas une stratégie
multi-utilisateur. Un `MemoryManager` fourni explicitement ne doit pas être partagé
entre conversations concurrentes. Les outils partagés doivent eux aussi supporter
la concurrence ou être instanciés par session.

L'application possède l'executor, le client, les outils et les repositories qu'elle
fournit ; Memchat ne les ferme pas. Memchat ferme son propre dispatcher. Terminer
les sessions avant d'arrêter l'executor applicatif. Aucun thread principal ni hook
Spring n'est requis dans le noyau.

## Migration

`void run(String)` devient `AgentRunResult run(String)`. Un appel ignorant le retour
reste valide après recompilation. La compatibilité binaire et les overrides de la
méthode ne sont pas préservés. Les anciennes captures d'exceptions checked de
`run()` doivent être adaptées aux résultats structurés. La CLI utilise le retour direct pour afficher la réponse finale avant la prochaine
saisie, sans dépendre des événements. Elle affiche également les issues non réussies et arrête sa boucle en cas d'annulation ou de récupération
requise.

Le retour de `run()` ne garantit plus que les callbacks sont livrés ; attendre la
fermeture si cette garantie est nécessaire. `AgentEvent` conserve son constructeur
à deux arguments mais ajoute des composants ; les consommateurs utilisant des
patterns de record ou des switches exhaustifs doivent être adaptés. L'API
`AgentCancellation` de la PR #8 non fusionnée est remplacée par le handle du run.
