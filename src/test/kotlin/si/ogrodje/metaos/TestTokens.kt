package si.ogrodje.metaos

/** realm_access of an approved user's token (Security.kt), for test JWTs: `jwt().jwt { it.claim("realm_access", APPROVED) }` */
val APPROVED: Map<String, Any> = mapOf("roles" to listOf("default-roles-metaos", MEMBER_ROLE, APPROVED_ROLE))

/** realm_access of a member's token, anyone who signed in (Security.kt) */
val MEMBER: Map<String, Any> = mapOf("roles" to listOf("default-roles-metaos", MEMBER_ROLE))
