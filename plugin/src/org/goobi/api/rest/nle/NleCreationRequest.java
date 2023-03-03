package org.goobi.api.rest.nle;

import javax.xml.bind.annotation.XmlRootElement;

import lombok.Data;

@Data
@XmlRootElement
public class NleCreationRequest {

    private String identifier; // b17046324
    private String  project; // goobi project, like SÄS
    private String  goobiWorkflow; // process template, like BOOKS_and continuing_publications _workflow
    private String  type; // publication type, like monograph
    private String  title; // main title, like This is my book
   
}
