package com.localroots.clientfiles.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameAttachmentRequest(
        @NotBlank @Size(max = 255) String displayName
) {
}
