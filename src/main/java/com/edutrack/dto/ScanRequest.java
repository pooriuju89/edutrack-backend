package com.edutrack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ScanRequest(
        @NotBlank(message = "QR code is required")
        String qrCode,

        /**
         * FIX: Added regexp = null allowance via nullable behaviour of @Pattern.
         * @Pattern skips validation when the value is null, so omitting scanType
         * from the request body is allowed — the controller defaults it to ARRIVAL.
         * The regex still rejects any non-null value that isn't ARRIVAL or DEPARTURE.
         */
        @Pattern(
            regexp  = "^(ARRIVAL|DEPARTURE)$",
            message = "scanType must be ARRIVAL or DEPARTURE"
        )
        String scanType
) {}
