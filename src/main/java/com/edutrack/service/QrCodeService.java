package com.edutrack.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

@Service
public class QrCodeService {

    @Value("${edutrack.qr.base-url}")
    private String baseUrl;

    /**
     * Generates a QR code PNG for the given token and returns it as a plain Base64 string
     * (no data-URI prefix). The frontend adds the prefix when rendering in an <img> tag.
     */
    public String generateQrBase64(String token, int size) throws Exception {
        String content = baseUrl + "/scan/" + token;

        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", baos);

        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }
}
