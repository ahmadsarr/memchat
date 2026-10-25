# Cycle de vie synchrone et résultat de l'agent

`AgentLoop` représente une conversation. `run(String)` exécute une demande sur le
thread appelant et retourne directement un `AgentRunResult`. La réponse finale
reste disponible même si aucun listener n'est abonné.

## Responsabilités et accès exclusif

Le noyau est **non thread-safe et non réentrant**. L'appelant doit assurer un accès
exclusif à chaque session, y compris à `run`, `subscribe`, `state`,
`recoveryRequired` et `close`, ainsi qu'à la mémoire associée. Un outil ou un
listener ne doit pas rappeler la session ou modifier sa mémoire pendant une
exécution. Les appels simultanés/réentrants sont hors contrat ; ils ne sont pas
interceptés par une protection interne.

L'ordonnancement, l'executor, la sérialisation des demandes, l'annulation externe
et la coordination de fermeture appartiennent à l'enveloppe applicative. Le noyau
ne fournit ni `submit()`, ni handle asynchrone, ni executor d'exécution par défaut.
Le dispatcher d'événements existant conserve sa livraison asynchrone ordonnée.

`AgentBuilder` est un builder mutable, pas une fabrique concurrente. Une application
peut créer un builder et une mémoire par conversation. Les chemins de stockage par
défaut ne constituent pas une isolation multi-utilisateur ; l'application doit
configurer des stockages distincts ou une stratégie d'accès appropriée.

## Utilisation

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

## États de la session

| État | Comportement |
|---|---|
| `OPEN`, sans récupération requise | Accepte des appels successifs à `run()` |
| `OPEN`, avec `recoveryRequired=true` | Refuse de nouvelles exécutions ; peut être fermée |
| `CLOSED` | Refuse `run()` et les nouvelles subscriptions ; `close()` est sans effet |

La construction ouvre la session. Le retour de `run()` laisse la session ouverte,
sauf qu'un échec peut nécessiter une récupération. `close()` effectue la transition
irréversible `OPEN -> CLOSED` après livraison des événements acceptés.

La fermeture doit être appelée par le propriétaire de la session **après la fin
de `run()`**, jamais depuis un outil ou un listener. Elle est idempotente et attend
le dispatcher. Elle ne demande pas l'annulation d'une exécution en cours et n'offre
pas de délai maximum. Un listener qui ne termine pas peut bloquer la fermeture ;
la gestion de ces délais appartient à l'enveloppe applicative.

La session possède son dispatcher et le ferme. Elle ne ferme pas les clients,
outils ou repositories fournis par l'application.

## Résultat d'une demande

Chaque demande démarrée reçoit un `runId` unique. `AgentRunResult` contient :

- `status` : `SUCCEEDED`, `CANCELLED`, `FAILED` ou `LIMIT_REACHED` ;
- `finalResponse` : non nulle uniquement pour `SUCCEEDED`, y compris lorsqu'elle
  provient d'un outil terminal ;
- `failure` : diagnostic présent uniquement pour `FAILED` (phase, type, message) ;
- `stepsExecuted` : nombre de tentatives d'appel du modèle dans la boucle,
  hors compaction ;
- `recoveryRequired` : indique qu'une nouvelle exécution serait dangereuse sans
  vérification de la mémoire et des éventuels effets déjà produits.

`AgentRunState` décrit uniquement ces résultats terminaux, pas les états d'une
future ou d'un executor. `LIMIT_REACHED` n'est pas une réussite et n'expose pas
une réponse intermédiaire comme réponse finale.

Une instruction invalide, une session fermée ou une récupération requise lèvent
une exception avant d'ajouter un message et ne produisent pas de résultat terminal.
Les erreurs ordinaires après démarrage sont représentées dans `AgentRunResult`.
Les erreurs JVM de type `Error` marquent la session en récupération, publient un
résultat terminal d'échec, puis sont propagées au demandeur.

Une erreur ordinaire d'un outil reste un `ToolResult` d'échec fourni au modèle,
conformément au fonctionnement existant ; elle ne rend pas forcément le run `FAILED`.

## Interruption et mémoire

Le noyau observe l'interruption Java entre les opérations : avant l'instruction,
le contexte/compaction, le modèle, le traitement de sa réponse et l'exécution d'un
outil. Une interruption observée conduit à `CANCELLED` et son drapeau est conservé.
Les opérations bloquantes déjà engagées ne sont pas arrêtées de force.

Un outil déjà exécuté a son résultat enregistré avant le retour. Si un appel a été
enregistré mais que l'interruption empêche son exécution, un résultat correspondant
est enregistré pour préserver la cohérence de l'historique. Les effets externes
ne sont pas annulés rétroactivement.

Un échec du modèle ou une réponse invalide ne bloque pas automatiquement une mémoire
restée cohérente. Une exception pendant l'écriture d'un message ou entre l'appel
d'outil enregistré et son résultat marque en revanche `recoveryRequired`. L'échec
de persistance reste `FAILED`, même si le thread est également interrompu.

La récupération est conservatrice : inspecter/réparer les stockages et les éventuels
effets externes, fermer la session, puis en créer une nouvelle avec une mémoire
cohérente. Pas de remise à zéro aveugle du flag ni de relance automatique d'outil.

L'enveloppe peut utiliser les mécanismes Java d'exécution. Elle doit distinguer
l'annulation d'une future de la fin effective de `run()` et attendre cette dernière
avant de fermer ou réutiliser la conversation. Une future annulée ne donne pas, à
elle seule, cette garantie.

## Événements et affichage

Les événements portent `runId`. L'événement `RUN_COMPLETED` porte le même objet
`AgentRunResult` que le retour synchrone, et est publié une seule fois par demande
terminée dans le cadre du contrat. `AGENT_MESSAGE` contient la réponse finale et
`TOOL_EXECUTION` signale le début d'un appel d'outil. `TOOL_COMPLETED` transmet
ensuite son résultat réel via `toolExecution` : identifiant d'appel, nom de
l'outil, `ToolResult` et durée en millisecondes. Ces données sont conservées
avec le `runId` lors de la livraison aux listeners.

Un résultat d'outil en échec est affiché comme tel dans la TUI, avec sa cause,
même si l'agent récupère ensuite et termine avec `SUCCEEDED`. Il ne constitue
pas un échec terminal de la demande. La notification décrit l'exécution de
l'outil ; elle ne garantit pas que son résultat a été persisté en mémoire.

Dans la TUI, l'activité d'un outil reste visible tant que l'appel est en cours.
Son état final et sa notification d'échec disparaissent après trois secondes,
sans nécessiter de nouvelle saisie. Le diagnostic d'échec reste dans le
transcript ; une question en attente de réponse ne disparaît pas à cette échéance.

`run()` n'attend pas les listeners. Les événements peuvent être livrés avant ou
après son retour, et un listener lent retarde les événements suivants ainsi que
la fermeture. La notification est en mémoire et n'est pas durable après un crash.

La CLI affiche la réponse finale depuis le retour direct, puis demande la saisie
suivante. Elle ne réaffiche pas la réponse à réception de `AGENT_MESSAGE` ou de
`RUN_COMPLETED`. Les événements d'outils continuent d'afficher la progression.
Elle utilise `try-with-resources` et interrompt sa boucle en cas d'annulation ou
de récupération requise.

## Migration

`void run(String)` devient `AgentRunResult run(String)`. Les appels ignorant le
retour restent valides après recompilation ; la compatibilité binaire et les
overrides ne sont pas conservés. Les captures des anciennes exceptions checked
de `run()` doivent être adaptées aux résultats structurés.

Le retour de `run()` ne garantit plus la livraison des callbacks ; la fermeture
les draine. `AgentEvent` conserve son constructeur à deux arguments mais ajoute
des composants : adapter les patterns de record et switches exhaustifs concernés.
