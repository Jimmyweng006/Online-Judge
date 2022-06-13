package com.example

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.response.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.http.*
import io.ktor.client.*
import io.ktor.client.engine.apache.*
import io.ktor.features.*
import io.ktor.jackson.*
import io.ktor.sessions.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import redis.clients.jedis.Jedis

const val NORMAL_USER_AUTHENTICAION_NAME = "Normal User"
const val SUPER_USER_AUTHENTICATION_NAME = "Super User"
const val SUBMISSION_NO_RESULT = "-"

fun main(args: Array<String>): Unit = io.ktor.server.netty.EngineMain.main(args)

fun initDatabase() {
    val config = HikariConfig("/hikari.properties")
    config.schema = "public"
    val dataSource = HikariDataSource(config)
    Database.connect(dataSource)

    transaction {
        SchemaUtils.create(ProblemTable, TestCaseTable, UserTable, SubmissionTable)
    }
}

@Suppress("unused") // Referenced in application.conf
@kotlin.jvm.JvmOverloads
fun Application.module(testing: Boolean = false) {
    initDatabase()

    var jedis: Jedis? = Jedis()

    val client = HttpClient(Apache) {
    }

    install(ContentNegotiation) {
        jackson {
            enable(SerializationFeature.INDENT_OUTPUT) // 開啟美化輸出出來的 JSON 的功能
        }
    }

    install(StatusPages) {
        exception<Throwable> {
            call.respond(HttpStatusCode.InternalServerError)
        }

        exception<com.fasterxml.jackson.core.JsonParseException> {
            call.respond(HttpStatusCode.BadRequest)
        }

        exception<com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException> {
            call.respond(HttpStatusCode.BadRequest)
        }

        exception<NotFoundException> {
            call.respond(HttpStatusCode.NotFound)
        }

        exception<IdAlreadyExistedException> {
            call.respond(HttpStatusCode.BadRequest)
        }

        exception<BadRequestException> {
            call.respond(HttpStatusCode.BadRequest)
        }

        exception<UnauthorizedException> {
            call.respond(HttpStatusCode.Unauthorized)
        }
    }

    install(Sessions) {
        cookie<UserIdAuthorityPrincipal>(
            "login_data",
            storage = SessionStorageMemory()
        ) {
            cookie.path = "/"
        }
    }

    install(Authentication) {
        session<UserIdAuthorityPrincipal>(NORMAL_USER_AUTHENTICAION_NAME) {
            challenge {
                throw UnauthorizedException()
            }
            validate { session: UserIdAuthorityPrincipal ->
                session
            }
        }

        session<UserIdAuthorityPrincipal>(SUPER_USER_AUTHENTICATION_NAME) {
            challenge {
                throw UnauthorizedException()
            }
            validate { session: UserIdAuthorityPrincipal ->
                if (session.authority.toInt() > 1) session else null
            }
        }
    }

    routing {
        get("/") {
            call.respondText("HELLO WORLD!", contentType = ContentType.Text.Plain)
        }

        route("/problems") {
            // 讀取全部的題目列表
            get {
                var problems: List<Map<String, Any>>? = null

                transaction {
                    problems = ProblemTable.selectAll().map {
                        mapOf(
                            "id" to it[ProblemTable.id].toString(),
                            "title" to it[ProblemTable.title]
                        )
                    }
                }

                call.respond(mapOf(
                        "data" to problems
                    )
                )
            }

            authenticate(SUPER_USER_AUTHENTICATION_NAME) {
                // 創建題目
                post {
                    val newProblem = call.receive<ProblemPostDTO>()
                    var newProblemId: Int? = null

                    transaction {
                        newProblemId = ProblemTable.insert {
                            it[title] = newProblem.title
                            it[description] = newProblem.description
                        } get ProblemTable.id

                        for (testCase in newProblem.testCases) {
                            TestCaseTable.insert {
                                it[input] = testCase.input
                                it[expectedOutput] = testCase.expectedOutput
                                it[comment] = testCase.comment
                                it[score] = testCase.score
                                it[timeOutSeconds] = testCase.timeOutSeconds
                                it[problemId] = newProblemId!!
                            }
                        }
                    }

                    call.respond(
                        mapOf(
                            "problem_id" to newProblemId
                        )
                    )
                }
            }

            route("/{id}") {
                // 讀取編號為 `id` 的題目
                get {
                    val requestId = call.parameters["id"]?.toInt() ?:
                    throw BadRequestException("The type of Id is wrong.")
                    var responseData: Problem? = null

                    transaction {
                        // also handle problem id not found case
                        val requestProblem = ProblemTable.select {
                            ProblemTable.id.eq(requestId)
                        }.firstOrNull() ?: throw NotFoundException()

                        val requestTestCases = TestCaseTable.select {
                            TestCaseTable.problemId.eq(requestId)
                        }.map {
                            TestCase(
                                id = it[TestCaseTable.id].toString(),
                                input = it[TestCaseTable.input],
                                expectedOutput = it[TestCaseTable.expectedOutput],
                                comment = it[TestCaseTable.comment],
                                score = it[TestCaseTable.score],
                                timeOutSeconds = it[TestCaseTable.timeOutSeconds]
                            )
                        }.toList()

                        responseData = Problem(
                            id = requestProblem[ProblemTable.id].toString(),
                            title = requestProblem[ProblemTable.title],
                            description = requestProblem[ProblemTable.description],
                            testCases = requestTestCases
                        )
                    }

                    call.respond(mapOf("data" to responseData))
                }

                authenticate(SUPER_USER_AUTHENTICATION_NAME) {
                    // 更新編號為 `id` 的題目
                    put {
                        val requestId =
                            call.parameters["id"]?.toInt() ?: throw BadRequestException("The type of Id is wrong.")
                        val updateProblemContent = call.receive<ProblemPutDTO>()

                        transaction {
                            ProblemTable.update({ ProblemTable.id.eq(requestId) }) {
                                it[ProblemTable.title] = updateProblemContent.title
                                it[ProblemTable.description] = updateProblemContent.description
                            }

                            TestCaseTable.deleteWhere {
                                TestCaseTable.problemId.eq(requestId)
                                    .and(TestCaseTable.id.notInList(
                                        updateProblemContent.testCases
                                            .mapNotNull { it.id?.toInt() }
                                    ))
                            }

                            for (testcase in updateProblemContent.testCases) {
                                if (testcase.id == null) {
                                    TestCaseTable.insert {
                                        it[TestCaseTable.input] = testcase.input
                                        it[TestCaseTable.expectedOutput] = testcase.expectedOutput
                                        it[TestCaseTable.comment] = testcase.comment
                                        it[TestCaseTable.score] = testcase.score
                                        it[TestCaseTable.timeOutSeconds] = testcase.timeOutSeconds
                                        it[TestCaseTable.problemId] = requestId
                                    }
                                    continue
                                }

                                TestCaseTable.update({ TestCaseTable.id.eq(testcase.id.toInt()) }) {
                                    it[TestCaseTable.input] = testcase.input
                                    it[TestCaseTable.expectedOutput] = testcase.expectedOutput
                                    it[TestCaseTable.comment] = testcase.comment
                                    it[TestCaseTable.score] = testcase.score
                                    it[TestCaseTable.timeOutSeconds] = testcase.timeOutSeconds
                                }
                            }
                        }

                        call.respond(
                            mapOf(
                                "OK" to true
                            )
                        )
                    }

                    // 刪除編號為 `id` 的題目
                    delete {
                        val requestId =
                            call.parameters["id"]?.toInt() ?: throw BadRequestException("The type of Id is wrong.")

                        transaction {
                            TestCaseTable.deleteWhere { TestCaseTable.problemId.eq(requestId) }
                            ProblemTable.deleteWhere { ProblemTable.id.eq(requestId) }
                        }

                        call.respond(
                            mapOf(
                                "OK" to true
                            )
                        )
                    }
                }
            }
        }

        route("/users") {
            post {
                val userData = call.receive<UserPostDTO>()
                var userId: Int? = null

                transaction {
                    userId = UserTable.insert {
                        it[username] = userData.username
                        it[password] =
                            PasswordHasher.hashPassword(userData.password)
                        it[name] = userData.name
                        it[email] = userData.email
                        it[authority] = 1 // 預設填入一個基本權限
                    } get UserTable.id
                }

                call.respond(mapOf(
                    "user_id" to userId
                ))
            }

            post("/login") {
                val userLoginDTO = call.receive<UserLoginDTO>()
                var userId: Int? = null
                var authority: Int? = null

                transaction {
                    val userData = UserTable.select { UserTable.username.eq(userLoginDTO.username) }.firstOrNull()
                        ?: throw BadRequestException("Authentication Error.")

                    if (!PasswordHasher.verifyPassword(
                            userLoginDTO.password,
                            userData[UserTable.password]
                        )) {
                        throw BadRequestException("Authentication Error.")
                    }

                    userId = userData.get(UserTable.id)
                    authority = userData.get(UserTable.authority)
                }

                if (userId == null || authority == null) throw BadRequestException("Authentication Error.")

                call.sessions.set("login_data", UserIdAuthorityPrincipal(userId.toString(), authority.toString()))
                call.respond(mapOf(
                    "OK" to true
                ))
            }

            post("/logout") {
                call.sessions.clear("login_data")
                call.respond(mapOf(
                    "OK" to true
                ))
            }
        }

        route("/submissions") {
            authenticate(NORMAL_USER_AUTHENTICAION_NAME) {
                post {
                    val submissionData = call.receive<SubmissionPostDTO>()
                    val userIdAuthorityPrincipal = call.sessions.get<UserIdAuthorityPrincipal>()
                    val userId = userIdAuthorityPrincipal?.userId
                    var submissionId: Int? = null
                    var testCaseData: List<JudgerTestCaseData>? = null

                    // does not need?
                    if (userId == null) throw UnauthorizedException()

                    transaction {
                        submissionId = SubmissionTable.insert {
                            it[language] = submissionData.language
                            it[code] = submissionData.code
                            it[executedTime] = -1.0
                            it[result] = "-"
                            it[problemId] = submissionData.problemId
                            it[SubmissionTable.userId] = userId.toInt()
                        } get SubmissionTable.id

                        testCaseData = TestCaseTable.select {
                            TestCaseTable.problemId.eq(submissionData.problemId)
                        }.map {
                            JudgerTestCaseData(
                                it[TestCaseTable.input],
                                it[TestCaseTable.expectedOutput],
                                it[TestCaseTable.score],
                                it[TestCaseTable.timeOutSeconds]
                            )
                        }.toList()
                    }

                    val judgerSubmissionId: Int? = submissionId
                    val judgerTestCaseData: List<JudgerTestCaseData>? = testCaseData
                    if (judgerSubmissionId != null && judgerTestCaseData != null) {
                        val judgerSubmissionData = JudgerSubmissionData(
                            judgerSubmissionId,
                            submissionData.language,
                            submissionData.code,
                            judgerTestCaseData
                        )

                        try {
                            jedis = jedis.getConnection()
                            val currentJedisConnection: Jedis = jedis!!
                            currentJedisConnection.rpush(
                                submissionData.language,
                                jacksonObjectMapper().writeValueAsString(judgerSubmissionData)
                            )
                            currentJedisConnection.disconnect()
                        } catch (e: Exception) {
                            jedis?.disconnect()
                            jedis = null
                            println(e)
                        }
                    }

                    call.respond(mapOf(
                        "submission_id" to submissionId
                    ))
                }

                authenticate(SUPER_USER_AUTHENTICATION_NAME) {
                    post("/restart") {
                        var unjudgedSubmissionData: List<JudgerSubmissionData>? = null
                        var isOK = true

                        // 尋找未審核的程式碼資料
                        transaction {
                            unjudgedSubmissionData =
                                SubmissionTable.select {
                                    SubmissionTable.result.eq(SUBMISSION_NO_RESULT)
                                }.map {
                                    val testCaseData = TestCaseTable.select {
                                        TestCaseTable.problemId.eq(it[SubmissionTable.problemId])
                                    }.map {
                                        JudgerTestCaseData(
                                            it[TestCaseTable.input],
                                            it[TestCaseTable.expectedOutput],
                                            it[TestCaseTable.score],
                                            it[TestCaseTable.timeOutSeconds]
                                        )
                                    }

                                    JudgerSubmissionData(
                                        it[SubmissionTable.id],
                                        it[SubmissionTable.language],
                                        it[SubmissionTable.code],
                                        testCaseData
                                    )
                                }.toList()
                        }

                        // 將資料丟入 Redis 資料庫中
                        val judgerSubmissionDataList = unjudgedSubmissionData
                        if (judgerSubmissionDataList != null) {
                            for (judgerSubmissionData in judgerSubmissionDataList) {
                                try {
                                    jedis = jedis.getConnection()
                                    val currentJedisConnection: Jedis = jedis!!
                                    currentJedisConnection.rpush(
                                        judgerSubmissionData.language,
                                        jacksonObjectMapper().writeValueAsString(judgerSubmissionData)
                                    )
                                } catch (e: Exception){
                                    jedis?.disconnect()
                                    jedis = null
                                    println(e)

                                    // 紀錄是否出現錯誤
                                    isOK = false
                                }
                            }
                        }

                        // 回傳是否有成功
                        call.respond(mapOf(
                            "OK" to isOK
                        ))
                    }
                }

                route("/{id}") {
                    get {
                        val requestId = call.parameters["id"]?.toInt() ?:
                        throw BadRequestException("The type of Id is wrong.")

                        var responseData: Submission? = null
                        val userIdAuthorityPrincipal = call.sessions.get<UserIdAuthorityPrincipal>()
                        val userId = userIdAuthorityPrincipal?.userId ?: throw UnauthorizedException()

                        transaction {
                            val requestSubmission = SubmissionTable.select {
                                SubmissionTable.id.eq(requestId)
                            }.first()

                            if (requestSubmission[SubmissionTable.userId] != userId.toInt()) {
                                throw UnauthorizedException()
                            }

                            responseData = Submission(
                                id = requestSubmission[SubmissionTable.id],
                                language = requestSubmission[SubmissionTable.language],
                                code = requestSubmission[SubmissionTable.code],
                                executedTime = requestSubmission[SubmissionTable.executedTime],
                                result = requestSubmission[SubmissionTable.result],
                                problemId = requestSubmission[SubmissionTable.problemId],
                                userId = requestSubmission[SubmissionTable.userId]
                            )
                        }

                        call.respond(mapOf(
                            "data" to responseData
                        ))
                    }

                    post("/restart") {
                        val requestId = call.parameters["id"]?.toInt() ?:
                        throw BadRequestException("The type of Id is wrong.")
                        var unjudgedSubmissionData: JudgerSubmissionData? = null
                        var isOK = true
                        val userIdAuthorityPrincipal = call.sessions.get<UserIdAuthorityPrincipal>()
                        val userId = userIdAuthorityPrincipal?.userId ?: throw UnauthorizedException()

                        transaction {
                            val submissionData =
                                SubmissionTable.select {
                                    SubmissionTable.id.eq(requestId)
                                }.first()

                            // 非該用戶所遞交的程式碼，直接丟入 UnauthorizedException
                            if (submissionData[SubmissionTable.userId] != userId.toInt()) {
                                throw UnauthorizedException()
                            }

                            val testCaseData =
                                TestCaseTable.select {
                                    TestCaseTable.problemId.eq(submissionData[SubmissionTable.problemId])
                                }.map {
                                    JudgerTestCaseData(
                                        it[TestCaseTable.input],
                                        it[TestCaseTable.expectedOutput],
                                        it[TestCaseTable.score],
                                        it[TestCaseTable.timeOutSeconds]
                                    )
                                }

                            unjudgedSubmissionData = JudgerSubmissionData(
                                submissionData[SubmissionTable.id],
                                submissionData[SubmissionTable.language],
                                submissionData[SubmissionTable.code],
                                testCaseData
                            )
                        }

                        // 將資料丟入 Redis 資料庫中
                        val judgerSubmissionData = unjudgedSubmissionData
                        if (judgerSubmissionData != null) {
                            try {
                                jedis = jedis.getConnection()
                                val currentJedisConnection: Jedis = jedis!!
                                currentJedisConnection.rpush(
                                    judgerSubmissionData.language,
                                    jacksonObjectMapper().writeValueAsString(judgerSubmissionData)
                                )
                            } catch (e: Exception) {
                                println(e)
                                jedis?.disconnect()
                                jedis = null

                                // 紀錄是否出現錯誤
                                isOK = false
                            }
                        }

                        // 回傳是否有成功
                        call.respond(mapOf(
                            "OK" to isOK
                        ))
                    }
                }
            }
        }
    }
}