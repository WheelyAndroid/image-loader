package com.wheely.imageloader

import java.io.IOException

class HttpException(val code: Int, override val message: String) : IOException(message)
