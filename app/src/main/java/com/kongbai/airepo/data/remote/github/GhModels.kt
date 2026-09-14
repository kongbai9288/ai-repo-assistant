package com.kongbai.airepo.data.remote.github

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GhUser(
    val login: String = "",
    val id: Long = 0,
    @Json(name = "avatar_url") val avatarUrl: String = "",
    val name: String? = null,
    val bio: String? = null,
    @Json(name = "public_repos") val publicRepos: Int = 0,
    @Json(name = "total_private_repos") val privateRepos: Int? = null
)

@JsonClass(generateAdapter = true)
data class GhRepo(
    val id: Long = 0,
    val name: String = "",
    @Json(name = "full_name") val fullName: String = "",
    val description: String? = null,
    @Json(name = "private") val isPrivate: Boolean = false,
    @Json(name = "stargazers_count") val stars: Int = 0,
    @Json(name = "default_branch") val defaultBranch: String = "main",
    val language: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "open_issues_count") val openIssues: Int = 0,
    val fork: Boolean = false,
    @Json(name = "html_url") val htmlUrl: String = "",
    val owner: GhUser? = null,
    val permissions: Map<String, Boolean>? = null
)

@JsonClass(generateAdapter = true)
data class GhContent(
    val name: String = "",
    val path: String = "",
    val sha: String? = null,
    val size: Long = 0,
    val type: String = "file",
    val content: String? = null,
    val encoding: String? = null,
    @Json(name = "html_url") val htmlUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class GhCommitRef(
    val sha: String = "",
    val message: String? = null,
    @Json(name = "html_url") val htmlUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class GhBranch(
    val name: String = "",
    val commit: GhCommitRef? = null,
    val protected: Boolean = false
)

@JsonClass(generateAdapter = true)
data class GhCommit(
    val sha: String = "",
    @Json(name = "html_url") val htmlUrl: String? = null,
    val commit: GhCommitDetail? = null
)

@JsonClass(generateAdapter = true)
data class GhCommitDetail(
    val message: String? = null,
    val author: GhAuthor? = null
)

@JsonClass(generateAdapter = true)
data class GhAuthor(val name: String? = null, val date: String? = null)

@JsonClass(generateAdapter = true)
data class GhIssue(
    val number: Int = 0,
    val title: String = "",
    val state: String = "open",
    val body: String? = null,
    @Json(name = "html_url") val htmlUrl: String = "",
    val user: GhUser? = null
)

@JsonClass(generateAdapter = true)
data class GhPull(
    val number: Int = 0,
    val title: String = "",
    val state: String = "open",
    val body: String? = null,
    @Json(name = "html_url") val htmlUrl: String = "",
    val head: GhRefSide? = null,
    val base: GhRefSide? = null,
    val user: GhUser? = null,
    val merged: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class GhRefSide(val ref: String = "", val sha: String = "")

@JsonClass(generateAdapter = true)
data class GhTree(
    val sha: String = "",
    val tree: List<GhTreeEntry> = emptyList(),
    val truncated: Boolean = false
)

@JsonClass(generateAdapter = true)
data class GhTreeEntry(
    val path: String = "",
    val type: String = "blob",
    val sha: String = "",
    val size: Long? = null
)

@JsonClass(generateAdapter = true)
data class GhCodeItem(
    val name: String = "",
    val path: String = "",
    val sha: String = "",
    @Json(name = "html_url") val htmlUrl: String = "",
    val repository: GhRepo? = null
)

@JsonClass(generateAdapter = true)
data class GhSearchCode(
    @Json(name = "total_count") val totalCount: Int = 0,
    val items: List<GhCodeItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GhSearchRepos(
    @Json(name = "total_count") val totalCount: Int = 0,
    val items: List<GhRepo> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GhPutContentResponse(
    val content: GhContent? = null,
    val commit: GhCommitRef? = null
)

@JsonClass(generateAdapter = true)
data class GhTokenResponse(
    @Json(name = "access_token") val accessToken: String? = null,
    val scope: String? = null,
    @Json(name = "token_type") val tokenType: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null
)
