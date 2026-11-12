package io.max.paperplay.core.dupes

actual fun defaultHttpPoster(): HttpPoster = UrlConnectionHttp()
