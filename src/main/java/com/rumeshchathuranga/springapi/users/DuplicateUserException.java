package com.rumeshchathuranga.springapi.users;

public class DuplicateUserException extends RuntimeException{
    public DuplicateUserException(){
        super("Duplicate User");
    }
}
