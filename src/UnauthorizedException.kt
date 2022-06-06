package com.example

class UnauthorizedException(message: String? = "Authentication Error.") : Exception(message)