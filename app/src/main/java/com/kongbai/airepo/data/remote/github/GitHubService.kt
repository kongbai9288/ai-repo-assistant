package com.kongbai.airepo.data.remote.github

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface GitHubService {

    @GET("user")
    suspend fun me(): GhUser

    @GET("user/repos")
    suspend fun listMyRepos(
        @Query("per_page") perPage: Int = 100,
        @Query("sort") sort: String = "updated",
        @Query("affiliation") affiliation: String = "owner,collaborator,organization_member"
    ): List<GhRepo>

    @POST("user/repos")
    suspend fun createRepo(@Body body: Map<String, @JvmSuppressWildcards Any?>): GhRepo

    @GET("repos/{owner}/{repo}")
    suspend fun getRepo(@Path("owner") owner: String, @Path("repo") repo: String): GhRepo

    @DELETE("repos/{owner}/{repo}")
    suspend fun deleteRepo(@Path("owner") owner: String, @Path("repo") repo: String): Response<Unit>

    @GET("repos/{owner}/{repo}/contents/{path}")
    suspend fun getContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Query("ref") ref: String? = null
    ): Response<ResponseBody>

    @PUT("repos/{owner}/{repo}/contents/{path}")
    suspend fun putContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): GhPutContentResponse

    @DELETE("repos/{owner}/{repo}/contents/{path}")
    suspend fun deleteContent(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("path", encoded = true) path: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): GhPutContentResponse

    @GET("repos/{owner}/{repo}/git/trees/{ref}")
    suspend fun getTree(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("ref") ref: String,
        @Query("recursive") recursive: Int = 1
    ): GhTree

    @GET("repos/{owner}/{repo}/branches")
    suspend fun listBranches(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("per_page") perPage: Int = 100
    ): List<GhBranch>

    @POST("repos/{owner}/{repo}/git/refs")
    suspend fun createRef(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Map<String, @JvmSuppressWildcards Any?>

    @GET("repos/{owner}/{repo}/commits")
    suspend fun listCommits(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("sha") sha: String? = null,
        @Query("path") path: String? = null,
        @Query("per_page") perPage: Int = 20
    ): List<GhCommit>

    @GET("repos/{owner}/{repo}/issues")
    suspend fun listIssues(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("state") state: String = "open",
        @Query("per_page") perPage: Int = 20
    ): List<GhIssue>

    @POST("repos/{owner}/{repo}/issues")
    suspend fun createIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): GhIssue

    @POST("repos/{owner}/{repo}/issues/{number}/comments")
    suspend fun commentIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Map<String, @JvmSuppressWildcards Any?>

    @GET("repos/{owner}/{repo}/pulls")
    suspend fun listPulls(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Query("state") state: String = "open",
        @Query("per_page") perPage: Int = 20
    ): List<GhPull>

    @POST("repos/{owner}/{repo}/pulls")
    suspend fun createPull(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): GhPull

    @PUT("repos/{owner}/{repo}/pulls/{number}/merge")
    suspend fun mergePull(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Path("number") number: Int,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Map<String, @JvmSuppressWildcards Any?>

    @Headers("Accept: application/vnd.github.text-match+json")
    @GET("search/code")
    suspend fun searchCode(
        @Query("q") q: String,
        @Query("per_page") perPage: Int = 10
    ): GhSearchCode

    @GET("search/repositories")
    suspend fun searchRepos(
        @Query("q") q: String,
        @Query("per_page") perPage: Int = 10
    ): GhSearchRepos

}
