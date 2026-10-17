package org.iaproject.memchat.tools;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Inherited()
public @interface Tool {
    String description();

    boolean terminal() default false;
}
