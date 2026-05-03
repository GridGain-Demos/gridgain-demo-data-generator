package com.gridgain.demo.datagen.provisioning

data class ColumnDescriptor(val name: String, val type: SqlType, val isKey: Boolean, val isAffinity: Boolean)
