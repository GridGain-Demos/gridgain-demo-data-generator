package com.gridgain.demo.datagen.errors

open class DomainException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MisconfigurationException(
    message: String, cause: Throwable? = null
) : DomainException(message, cause)

class CorruptedStateException(
    message: String, cause: Throwable? = null
) : DomainException(message, cause)
