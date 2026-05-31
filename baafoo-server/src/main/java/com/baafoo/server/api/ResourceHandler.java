package com.baafoo.server.api;

interface ResourceHandler {
    Object handle(String method, String path, String body, ApiContext ctx) throws Exception;
}
