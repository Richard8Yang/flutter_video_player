package com.richardyang.video_player


import android.opengl.GLES32.*
import android.opengl.GLES11Ext.*
import android.util.Log
import java.nio.FloatBuffer


class VideoRenderWorker {

    lateinit var vertexBuffer4FBO: FloatBuffer;
    lateinit var textureBuffer4FBO: FloatBuffer;

    var _program: Int? = null;

    var openGLProgram: OpenGLProgram;
    var srcTextureId: Int = -1;
    var dstTextureId: Int = -1;
    var fboId: Int = -1;
    var rboId: Int = -1;

    var offScreenProgram: Int = -1;
    var onScreenProgram: Int = -1;

    var glWidth: Int = 640;
    var glHeight: Int = 480;

    constructor() {
        this.openGLProgram = OpenGLProgram();
    }

    fun upperPowerOfTwo(value: Int): Int {
        /*
        var v = value
        v = v - 1
        v = v or (v shr 1)
        v = v or (v shr 2)
        v = v or (v shr 4)
        v = v or (v shr 8)
        v = v or (v shr 16)
        return v + 1
        */
        return value
    }

    fun setup() {
        setupVBO4FBO()

        var texArr: IntArray = IntArray(2)
        glGenTextures(2, texArr, 0)
        srcTextureId = texArr[0]
        dstTextureId = texArr[1]

        // create source external texture to receive video frame data
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, srcTextureId);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        checkGlError("Bind OES texture")

        Log.d("Thread %d".format(Thread.currentThread().getId()), "Frame texture name %d".format(srcTextureId))

        val w = upperPowerOfTwo(glWidth);
        val h = upperPowerOfTwo(glHeight);
        Log.d("SETUP", "FBO size: %d x %d".format(w, h));

        // create destination texture as FBO to be drawn to
        glBindTexture(GL_TEXTURE_2D, dstTextureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);
        glBindTexture(GL_TEXTURE_2D, 0);

        // create a renderbuffer object to store depth info
        var rboArr: IntArray = IntArray(1);
        glGenRenderbuffers(1, rboArr, 0);
        rboId = rboArr[0];
        glBindRenderbuffer(GL_RENDERBUFFER, rboId);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, w, h);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);

        // create a framebuffer object
        var fboArr: IntArray = IntArray(1);
        glGenFramebuffers(1, fboArr, 0);
        fboId = fboArr[0];
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);

        // attach the texture to FBO color attachment point
        glFramebufferTexture2D(GL_FRAMEBUFFER,        // 1. fbo target: GL_FRAMEBUFFER
                            GL_COLOR_ATTACHMENT0,  // 2. attachment point
                            GL_TEXTURE_2D,         // 3. tex target: GL_TEXTURE_2D
                            dstTextureId,             // 4. tex ID
                            0);                    // 5. mipmap level: 0(base)

        // attach the renderbuffer to depth attachment point
        glFramebufferRenderbuffer(GL_FRAMEBUFFER,      // 1. fbo target: GL_FRAMEBUFFER
                                GL_DEPTH_ATTACHMENT, // 2. attachment point
                                GL_RENDERBUFFER,     // 3. rbo target: GL_RENDERBUFFER
                                rboId);              // 4. rbo ID

        // check FBO status
        var status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            Log.e("FBO", "Failed to init FBO!")
            return
        }

        // switch back to window-system-provided framebuffer
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    fun updateTextureSize(width: Int, height: Int) {
        glWidth = width;
        glHeight = height;
        val w = upperPowerOfTwo(glWidth);
        val h = upperPowerOfTwo(glHeight);
        glBindRenderbuffer(GL_RENDERBUFFER, rboId);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, w, h);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, dstTextureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    fun renderTexture(matrix: FloatArray?, drawOnScreen: Boolean = false) {
        drawOffScreenTexture(vertexBuffer4FBO, textureBuffer4FBO, matrix);
        if (drawOnScreen) {
            drawOnScreenTexture(vertexBuffer4FBO, textureBuffer4FBO);
        }
    }

    fun drawOffScreenTexture(vertexBuffer: FloatBuffer, textureBuffer: FloatBuffer, matrix: FloatArray?) {
        glBindFramebuffer(GL_FRAMEBUFFER, fboId);

        // clear buffers
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT);

        // Important: set viewport first
        glViewport(0, 0, glWidth, glHeight);

        if (offScreenProgram < 0) {
            offScreenProgram = openGLProgram.getProgramOES();
        }
        var program = offScreenProgram;
        glUseProgram(program)

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, srcTextureId);
        checkGlError("Bind OES texture")
        
        var texUniform = glGetUniformLocation(program, "Texture0")
        if (texUniform >= 0) {
            glUniform1i(texUniform, 1);
            checkGlError("Set texture uniform")
        }
        var resultMatrix = floatArrayOf(
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
        );

        if(matrix != null) {
            resultMatrix = matrix;
        }
        var _matrixUniform = glGetUniformLocation(program, "matrix")
        if (_matrixUniform >= 0) {
            glUniformMatrix4fv(_matrixUniform, 1, false, resultMatrix, 0)
        }
        checkGlError("Set matrix uniform")

        var _positionSlot = glGetAttribLocation(program, "Position");
        var _textureSlot = glGetAttribLocation(program, "TextureCoords");

        glEnableVertexAttribArray(_positionSlot);
        glEnableVertexAttribArray(_textureSlot);

        vertexBuffer.position(0);
        glVertexAttribPointer(_positionSlot, 3, GL_FLOAT, false, 0, vertexBuffer);

        textureBuffer.position(0);
        glVertexAttribPointer(_textureSlot, 2, GL_FLOAT, false, 0, textureBuffer);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");

        // switch back to window-system-provided framebuffer
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    fun drawOnScreenTexture(vertexBuffer: FloatBuffer, textureBuffer: FloatBuffer) {
        // Important: set viewport first
        glViewport(0, 0, glWidth, glHeight);

        if (onScreenProgram < 0) {
            onScreenProgram = openGLProgram.getProgram();
        }

        var program = onScreenProgram;
        glUseProgram(program)

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, dstTextureId);
        checkGlError("Bind texture")
        
        var texUniform = glGetUniformLocation(program, "Texture0")
        if (texUniform >= 0) {
            glUniform1i(texUniform, 0);
            checkGlError("Set texture uniform")
        }
        var resultMatrix = floatArrayOf(
                1.0f, 0.0f, 0.0f, 0.0f,
                0.0f, 1.0f, 0.0f, 0.0f,
                0.0f, 0.0f, 1.0f, 0.0f,
                0.0f, 0.0f, 0.0f, 1.0f
        );
        var _matrixUniform = glGetUniformLocation(program, "matrix")
        if (_matrixUniform >= 0) {
            glUniformMatrix4fv(_matrixUniform, 1, false, resultMatrix, 0)
        }

        var _positionSlot = glGetAttribLocation(program, "Position");
        var _textureSlot = glGetAttribLocation(program, "TextureCoords");

        glEnableVertexAttribArray(_positionSlot);
        glEnableVertexAttribArray(_textureSlot);

        vertexBuffer.position(0);
        glVertexAttribPointer(_positionSlot, 3, GL_FLOAT, false, 0, vertexBuffer);

        textureBuffer.position(0);
        glVertexAttribPointer(_textureSlot, 2, GL_FLOAT, false, 0, textureBuffer);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");
    }

    // 显示targetFrameBufferTexture
    fun renderWithFXAA(texture: Int, width: Int, height: Int) {
        var program = openGLProgram.getProgramByName("glsl-fxaa");
        glUseProgram(program)

        glActiveTexture(GL_TEXTURE10);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, texture);
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR.toFloat());
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR.toFloat());
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE.toFloat());
        glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE.toFloat());
        checkGlError("Bind OES texture")

        glUniform1i(glGetUniformLocation(program, "Texture0"), 10);
        // 激活纹理单元GL_TEXTURE0，绑定纹理，
        //  GL_TEXTURE0
        // 将 textureSlot 赋值为 0，而 0 与 GL_TEXTURE0 对应，这里如果写 1，上面也要改成 GL_TEXTURE1

        var frameBufferSize = floatArrayOf(width.toFloat(), height.toFloat());

        glUniform2fv(glGetUniformLocation(program, "frameBufSize"), 1, frameBufferSize, 0);

        var _positionSlot = glGetAttribLocation(program, "Position")
        var _textureSlot = glGetAttribLocation(program, "TextureCoords")
        glEnableVertexAttribArray(_positionSlot);
        glEnableVertexAttribArray(_textureSlot);

        vertexBuffer4FBO.position(0);
        // 设置顶点数据
        glVertexAttribPointer(_positionSlot, 3, GL_FLOAT, false, 0, vertexBuffer4FBO);

        textureBuffer4FBO.position(0);
        glVertexAttribPointer(_textureSlot, 2, GL_FLOAT, false, 0, textureBuffer4FBO);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    fun getProgram(): Int {
        if(_program == null) {
            _program = openGLProgram.getProgramOES();
            //_program = openGLProgram.getProgram();
        }
        return _program as Int;
    }

    fun setupVBO4FBO() {
        var w: Float = 1.0f;
        var h: Float = 1.0f;

        var verticesPoints = floatArrayOf(-w,-h,0.0f, w,-h,0.0f, -w,h,0.0f,  w,h,0.0f);
        var texturesPoints = floatArrayOf(0.0f,0.0f, 1.0f,0.0f, 0.0f,1.0f, 1.0f,1.0f);

        vertexBuffer4FBO = BufferUtils.createFloatBuffer(verticesPoints);
        textureBuffer4FBO = BufferUtils.createFloatBuffer(texturesPoints);
    }

    fun checkGlError(op: String) {
        val error: Int = glGetError();
        if (error != GL_NO_ERROR) {
            Log.e("ES30_ERROR", "$op: glError $error")
            throw RuntimeException("$op: glError $error")
        }
    }

    fun dispose() {

    }

}