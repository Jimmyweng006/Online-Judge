package com.example

import io.ktor.auth.*

data class UserIdAuthorityPrincipal(val userId: String, val authority: String) : Principal