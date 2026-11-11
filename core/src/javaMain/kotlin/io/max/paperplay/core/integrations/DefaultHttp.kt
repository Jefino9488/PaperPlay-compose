package io.max.paperplay.core.integrations

actual fun defaultHttp(): HttpFetcher = JavaHttp()
