package com.lambdanum.smsbackend.command;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CommandHandler {

    String value();

    UserRoleEnum requiredRole() default UserRoleEnum.INTRODUCED;

}
