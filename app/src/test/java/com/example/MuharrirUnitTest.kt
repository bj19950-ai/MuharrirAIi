package com.example

import com.example.data.pdf.PdfInspector
import com.example.data.pdf.PdfValidationCheck
import com.example.data.remote.BackendConnectionManager
import com.example.domain.model.BackendConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuharrirUnitTest {

    @Test
    fun `pdf signature validation accepts valid pdf header`() {
        val result = PdfInspector.validatePdfSignatureAndMetadata(
            fileName = "lecture_01.pdf",
            mimeType = "application/pdf",
            fileSizeBytes = 2048L,
            headerBytes = "%PDF-1.7".toByteArray(),
        )
        assertEquals(PdfValidationCheck.Passed, result)
    }

    @Test
    fun `pdf signature validation rejects empty and invalid files`() {
        val emptyResult = PdfInspector.validatePdfSignatureAndMetadata(
            fileName = "empty.pdf",
            mimeType = "application/pdf",
            fileSizeBytes = 0L,
            headerBytes = ByteArray(0),
        )
        assertTrue(emptyResult is PdfValidationCheck.Failed)

        val wrongType = PdfInspector.validatePdfSignatureAndMetadata(
            fileName = "notes.docx",
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            fileSizeBytes = 1024L,
            headerBytes = "PK\u0003\u0004".toByteArray(),
        )
        assertTrue(wrongType is PdfValidationCheck.Failed)

        val badHeader = PdfInspector.validatePdfSignatureAndMetadata(
            fileName = "fake.pdf",
            mimeType = "application/pdf",
            fileSizeBytes = 1024L,
            headerBytes = "NOTPDF".toByteArray(),
        )
        assertTrue(badHeader is PdfValidationCheck.Failed)
    }

    @Test
    fun `backend connection manager stays unconfigured without endpoint and builds retrofit when configured`() {
        val unconfigured = BackendConnectionManager("")
        assertTrue(unconfigured.connectionState is BackendConnectionState.NotConfigured)
        assertNull(unconfigured.createApiOrNull())

        val insecureHttp = BackendConnectionManager("http://example.com")
        assertTrue(insecureHttp.connectionState is BackendConnectionState.NotConfigured)
        assertNull(insecureHttp.createApiOrNull())

        val validHttps = BackendConnectionManager("https://api.muharrir.example.org")
        assertTrue(validHttps.connectionState is BackendConnectionState.Configured)
        assertNotNull(validHttps.createApiOrNull())
    }
}
