/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.webdemo.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.webdemo.ApplicationSettings;
import org.jetbrains.webdemo.CommonSettings;
import org.jetbrains.webdemo.ErrorWriter;
import org.jetbrains.webdemo.ArrowVersionsManager;
import org.jetbrains.webdemo.KotlinVersionsManager;
import org.jetbrains.webdemo.Project;
import org.jetbrains.webdemo.examples.ExamplesUtils;
import org.jetbrains.webdemo.help.HelpLoader;
import org.jetbrains.webdemo.session.SessionInfo;
import org.jetbrains.webdemo.session.UserInfo;
import org.jetbrains.webdemo.sessions.MyHttpSession;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class ServerHandler {
    private ObjectMapper objectMapper = new ObjectMapper();


    public void handle(final HttpServletRequest request, final HttpServletResponse response) throws IOException {

        if (request.getQueryString() != null && request.getQueryString().equals("test")) {
            try (PrintWriter out = response.getWriter()) {
                out.write("ok");
            } catch (Throwable e) {
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                        "TEST", request.getHeader("Origin"), "null");
            }
        } else {
            SessionInfo sessionInfo;
            try {
                switch (request.getParameter("type")) {
                    case ("loadHelpForWords"):
                        sendHelpContentForWords(request, response);
                        break;
                    case ("getArrowVersions"):
                        sendArrowVersions(request, response);
                        break;
                    case ("getKotlinVersions"):
                        sendKotlinVersions(request, response);
                        break;
                    case ("run"):
                        forwardRunRequest(request, response);
                        break;
                    case ("highlight"):
                        forwardHighlightRequest(request, response);
                        break;
                    case ("complete"):
                        forwardCompleteRequest(request, response);
                        break;
                    case ("convertToKotlin"):
                        forwardConvertResult(request, response);
                        break;
                    default: {
                        sessionInfo = setSessionInfo(request.getSession(), request.getHeader("Origin"));
                        MyHttpSession session = new MyHttpSession(sessionInfo);
                        session.handle(request, response);
                    }
                }
            } catch (Throwable e) {
                //Do not stop server
                ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e,
                        "UNKNOWN", "unknown", request.getRequestURI() + "?" + request.getQueryString());
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    private void forwardRunRequest(HttpServletRequest request, HttpServletResponse response) {
        try {
            Project project = objectMapper.readValue(request.getParameter("project"), Project.class);
            ExamplesUtils.addHiddenFilesToProject(project);
            ExamplesUtils.addUnmodifiableFilesToProject(project);
            Map<String, String> postParameters = new HashMap<>();
            postParameters.put("project", objectMapper.writeValueAsString(project));
            postParameters.put("filename", request.getParameter("filename"));
            postParameters.put("searchForMain", request.getParameter("searchForMain"));
            forwardRequestToBackend(request, response, postParameters);
        } catch (IOException e) {
            writeResponse(request, response, "Can't parse project", HttpServletResponse.SC_BAD_REQUEST);
        } catch (NullPointerException e) {
            writeResponse(request, response, "Can't get parameters", HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    private void forwardConvertResult(HttpServletRequest request, HttpServletResponse response) {
        Map<String, String> postParameters = new HashMap<>();
        postParameters.put("text", request.getParameter("text"));
        forwardRequestToBackend(request, response, postParameters);
    }

    private void forwardCompleteRequest(HttpServletRequest request, HttpServletResponse response) {
        try {
            Project project = objectMapper.readValue(request.getParameter("project"), Project.class);
            ExamplesUtils.addUnmodifiableFilesToProject(project);
            ExamplesUtils.addHiddenFilesToProject(project);
            Map<String, String> postParameters = new HashMap<>();
            postParameters.put("project", objectMapper.writeValueAsString(project));
            postParameters.put("filename", request.getParameter("filename"));
            postParameters.put("line", request.getParameter("line"));
            postParameters.put("ch", request.getParameter("ch"));
            forwardRequestToBackend(request, response, postParameters);
        } catch (IOException e) {
            writeResponse(request, response, "Can't parse project", HttpServletResponse.SC_BAD_REQUEST);
        } catch (NullPointerException e) {
            writeResponse(request, response, "Can't get parameters", HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    private void forwardHighlightRequest(HttpServletRequest request, HttpServletResponse response) {
        try {
            Project project = objectMapper.readValue(request.getParameter("project"), Project.class);
            ExamplesUtils.addHiddenFilesToProject(project);
            ExamplesUtils.addUnmodifiableFilesToProject(project);
            Map<String, String> postParameters = new HashMap<>();
            postParameters.put("project", objectMapper.writeValueAsString(project));
            forwardRequestToBackend(request, response, postParameters);
        } catch (IOException e) {
            writeResponse(request, response, "Can't parse project", HttpServletResponse.SC_BAD_REQUEST);
        } catch (NullPointerException e) {
            writeResponse(request, response, "Can't get parameters", HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    private void forwardRequestToBackend(HttpServletRequest request, HttpServletResponse response, Map<String, String> postParameters) {
        final boolean hasoutbody = (request.getMethod().equals("POST"));

        try {
            final URL url = new URL("http://" + ApplicationSettings.BACKEND_URL + "/"
                    + (request.getQueryString() != null ? "?" + request.getQueryString() : ""));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            final Enumeration<String> headers = request.getHeaderNames();
            while (headers.hasMoreElements()) {
                final String header = headers.nextElement();
                final Enumeration<String> values = request.getHeaders(header);
                while (values.hasMoreElements()) {
                    final String value = values.nextElement();
                    conn.addRequestProperty(header, value);
                }
            }


            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setUseCaches(false);
            conn.setDoOutput(hasoutbody);

            if (postParameters != null && !postParameters.isEmpty()) {
                conn.setRequestMethod("POST");
                try (OutputStream requestBody = conn.getOutputStream()) {
                    boolean first = true;
                    for (Map.Entry<String, String> entry : postParameters.entrySet()) {
                        if(entry.getValue() == null) continue;
                        if (first) {
                            first = false;
                        } else {
                            requestBody.write('&');
                        }
                        requestBody.write(URLEncoder.encode(entry.getKey(), "UTF8").getBytes());
                        requestBody.write('=');
                        requestBody.write(URLEncoder.encode(entry.getValue(), "UTF8").getBytes());
                    }
                }
            } else {
                conn.setRequestMethod("GET");
            }

            StringBuilder responseBody = new StringBuilder();
            if (conn.getResponseCode() >= 400) {
                StringBuilder serverMessage = new StringBuilder();
                try (InputStream errorStream = conn.getErrorStream()) {
                    if (errorStream != null) {
                        byte[] buffer = new byte[1024];
                        while (true) {
                            final int read = errorStream.read(buffer);
                            if (read <= 0) break;
                            serverMessage.append(new String(buffer, 0, read));
                        }
                    }
                }


                switch (conn.getResponseCode()) {
                    case HttpServletResponse.SC_NOT_FOUND:
                        responseBody.append("Kotlin compile server not found");
                        break;
                    case HttpServletResponse.SC_SERVICE_UNAVAILABLE:
                        responseBody.append("Kotlin compile server is temporary overloaded");
                        break;
                    default:
                        responseBody = serverMessage;
                        break;
                }
            } else {
                try (InputStream inputStream = conn.getInputStream()) {
                    if (inputStream != null) {
                        byte[] buffer = new byte[1024];
                        while (true) {
                            final int read = inputStream.read(buffer);
                            if (read <= 0) break;
                            responseBody.append(new String(buffer, 0, read));
                        }
                    }
                }
            }
            writeResponse(request, response, responseBody.toString(), conn.getResponseCode());
        } catch (SocketTimeoutException e) {
            writeResponse(request, response, "Compile server connection timeout", HttpServletResponse.SC_GATEWAY_TIMEOUT);
        } catch (Exception e) {
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, "FORWARD_REQUEST_TO_BACKEND", "", "Can't forward request to Kotlin compile server");
            writeResponse(request, response, "Can't send your request to Kotlin compile server", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private void sendArrowVersions(HttpServletRequest request, HttpServletResponse response) {
        try {
            String message = objectMapper.writeValueAsString(ArrowVersionsManager.INSTANCE.getArrowVersionConfigs());
            writeResponse(request, response, message, HttpServletResponse.SC_OK);
        } catch (IOException e) {
            ErrorWriter.log.error("Can't send Arrow versions", e);
            writeResponse(request, response, "Can't send Arrow versions", HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    private void sendKotlinVersions(HttpServletRequest request, HttpServletResponse response) {
        try {
            String message = objectMapper.writeValueAsString(KotlinVersionsManager.INSTANCE.getKotlinVersionConfigs());
            writeResponse(request, response, message, HttpServletResponse.SC_OK);
        } catch (IOException e) {
            ErrorWriter.log.error("Can't send Kotlin versions", e);
            writeResponse(request, response, "Can't send Kotlin versions", HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    @NotNull
    private SessionInfo setSessionInfo(final HttpSession session, String originUrl) {
        SessionInfo sessionInfo = new SessionInfo(session.getId());
        UserInfo userInfo = (UserInfo) session.getAttribute("userInfo");

        if (userInfo == null) {
            userInfo = new UserInfo();
            session.setAttribute("userInfo", userInfo);
        }
        sessionInfo.setUserInfo(userInfo);
        sessionInfo.setOriginUrl(originUrl);
        return sessionInfo;
    }

    private void sendHelpContentForWords(HttpServletRequest request, final HttpServletResponse response) {
        writeResponse(request, response, HelpLoader.getInstance().getHelpForWords(), 200);
    }


    //Send Response
    private void writeResponse(HttpServletRequest request, HttpServletResponse response, String responseBody, int statusCode) {
        try {
            response.addHeader("Cache-Control", "no-cache");
            response.setHeader("Access-Control-Allow-Origin", "*");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.setStatus(statusCode);
            if (!responseBody.equals("")) {
                try (PrintWriter writer = response.getWriter()) {
                    writer.write(responseBody);
                }
            }
        } catch (IOException e) {
            //This is an exception we can't send data to client
            ErrorWriter.ERROR_WRITER.writeExceptionToExceptionAnalyzer(e, "UNKNOWN", request.getHeader("Origin"), "null");
        }
    }
}
