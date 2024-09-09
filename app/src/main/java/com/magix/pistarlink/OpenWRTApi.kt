package com.magix.pistarlink

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.IOException

class OpenWRTApi(private val baseUrl: String, private val username: String, private val password: String) {

    private val client = OkHttpClient()

    /*Function to log in and retrieve the session token*/
    fun login(
        onSuccess: (JSONObject) -> Unit,
        onFailure: (String) -> Unit,
        retryCount: Int = 1,  // Number of retry attempts
        delayBetweenRetries: Long = 2000  // Delay between retries in milliseconds
    ) {
        Log.d("OpenWRT","Login API has been called.")
        val url = "$baseUrl/cgi-bin/luci/rpc/auth"
        val jsonPayload = """
        {
            "id": 1,
            "method": "login",
            "params": [
                "$username",
                "$password"
            ]
        }
    """.trimIndent()

        val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), jsonPayload)

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        fun attemptLogin(remainingRetries: Int) {
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (remainingRetries > 0) {
                        Log.w("Login", "Login failed: ${e.message}. Retrying in $delayBetweenRetries ms... ($remainingRetries retries left)")
                        // Retry after delay
                        Handler(Looper.getMainLooper()).postDelayed({
                            attemptLogin(remainingRetries - 1)
                        }, delayBetweenRetries)
                    } else {
                        onFailure("Failed to execute command after multiple attempts: ${e.message}")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: "No response body"
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            onSuccess(jsonResponse)
                        } catch (e: Exception) {
                            onFailure("Failed to parse JSON response: ${e.message}")
                        }
                    } else {
                        if (remainingRetries > 0) {
                            Log.w("Login", "Login failed with status code ${response.code}. Retrying in $delayBetweenRetries ms... ($remainingRetries retries left)")
                            // Retry after delay
                            Handler(Looper.getMainLooper()).postDelayed({
                                attemptLogin(remainingRetries - 1)
                            }, delayBetweenRetries)
                        } else {
                            onFailure("Failed to execute command after multiple attempts: ${response.code}")
                        }
                    }
                }
            })
        }

        // Start the first login attempt
        attemptLogin(retryCount)
    }


    /*Function to execute a command via LuCI RPC API
      1. System Board params: 'ubus call system board'
      2. System Info params: 'ubus call system info'
    */
    fun executeCommand(
        command: String,
        sysAuthToken: String,
        onSuccess: (JSONObject, Int) -> Unit,  // Passing the response code along with the JSONObject
        onFailure: (String, Int) -> Unit       // Passing the error message and response code
    ) {
        val url = "$baseUrl/cgi-bin/luci/rpc/sys"
        val jsonPayload = """
        {
            "method": "exec",
            "params": [
                "$command"
            ]
        }
    """.trimIndent()

        val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), jsonPayload)

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Cookie", "sysauth=$sysAuthToken")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // When the request fails due to an exception (like network failure), response code might be unavailable.
                onFailure("Failed to execute command: ${e.message}", -1) // -1 indicates no response code available.
            }

            override fun onResponse(call: Call, response: Response) {
                val responseCode = response.code  // Get the HTTP response code
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "No response body"
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        onSuccess(jsonResponse, responseCode)  // Pass the response code along with the response
                    } catch (e: Exception) {
                        onFailure("Failed to parse JSON response: ${e.message}", responseCode)
                    }
                } else {
                    onFailure("Failed to execute command with status code: $responseCode", responseCode)
                }
            }
        })
    }

    /* Fetch router uptime */
    fun getRouterUptime(
        sysAuthToken: String,
        onSuccess: (String) -> Unit,  // Return uptime as a string
        onFailure: (String) -> Unit
    ) {
        val url = "$baseUrl/cgi-bin/luci/rpc/sys"
        val jsonPayload = """
        {
            "method": "uptime"
        }
        """.trimIndent()

        val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), jsonPayload)

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Cookie", "sysauth=$sysAuthToken")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onFailure("Failed to fetch uptime: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "No response body"
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        val uptime = jsonResponse.optString("uptime", "Unknown")
                        onSuccess(uptime)
                    } catch (e: Exception) {
                        onFailure("Failed to parse JSON response: ${e.message}")
                    }
                } else {
                    onFailure("Failed to fetch uptime with status code: ${response.code}")
                }
            }
        })
    }
}

