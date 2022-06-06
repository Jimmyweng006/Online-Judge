package com.example

import org.jetbrains.exposed.sql.Table

class User {
}

object UserTable: Table() {
    val id = integer("UserId").autoIncrement().primaryKey()
    val username = varchar("Username", 255).uniqueIndex()
    val password = varchar("Password", 255)
    val name = varchar("Name", 255)
    val email = varchar("Email", 255)
    val authority = integer("Authority")
}

data class UserPostDTO (
    val username: String,
    val password: String,
    val name: String,
    val email: String
)

data class UserLoginDTO (
    val username: String,
    val password: String
)
