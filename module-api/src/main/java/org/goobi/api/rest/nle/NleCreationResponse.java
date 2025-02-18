package org.goobi.api.rest.nle;

import jakarta.xml.bind.annotation.XmlRootElement;

import lombok.Data;

@XmlRootElement
public @Data class NleCreationResponse {

    private String result; // success, error

    private String errorText;

    private String processName;

    private int processId;
}
