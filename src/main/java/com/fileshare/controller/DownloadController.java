package com.fileshare.controller;

import com.fileshare.service.DownloadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

@RestController
@RequestMapping("/api/v1/download")
@RequiredArgsConstructor
public class DownloadController {

    private final DownloadService downloadService;

    /**
     * Public endpoint — no JWT required.
     *
     * Validates the HMAC-signed token and issues a 302 redirect to a short-lived
     * DO Spaces presigned GET URL. The client downloads the file directly from DO Spaces,
     * keeping large file data off the application tier.
     *
     * @param token The HMAC-signed token from {@code POST /api/v1/files/{id}/download-link}
     */
    @GetMapping
    @ResponseStatus(HttpStatus.FOUND)
    public RedirectView download(
            @RequestParam String token,
            HttpServletRequest request) {

        String redirectUrl = downloadService.resolveDownloadRedirect(
                token,
                getClientIp(request),
                request.getHeader("User-Agent")
        );

        RedirectView redirectView = new RedirectView(redirectUrl);
        redirectView.setStatusCode(org.springframework.http.HttpStatus.FOUND);
        return redirectView;
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
