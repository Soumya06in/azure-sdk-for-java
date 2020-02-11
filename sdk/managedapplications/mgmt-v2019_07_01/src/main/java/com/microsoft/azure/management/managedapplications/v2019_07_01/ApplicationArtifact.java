/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 *
 * Code generated by Microsoft (R) AutoRest Code Generator.
 */

package com.microsoft.azure.management.managedapplications.v2019_07_01;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Managed application artifact.
 */
public class ApplicationArtifact {
    /**
     * The managed application artifact name. Possible values include:
     * 'NotSpecified', 'ViewDefinition', 'Authorizations',
     * 'CustomRoleDefinition'.
     */
    @JsonProperty(value = "name", required = true)
    private ApplicationArtifactName name;

    /**
     * The managed application artifact blob uri.
     */
    @JsonProperty(value = "uri", required = true)
    private String uri;

    /**
     * The managed application artifact type. Possible values include:
     * 'NotSpecified', 'Template', 'Custom'.
     */
    @JsonProperty(value = "type", required = true)
    private ApplicationArtifactType type;

    /**
     * Get the managed application artifact name. Possible values include: 'NotSpecified', 'ViewDefinition', 'Authorizations', 'CustomRoleDefinition'.
     *
     * @return the name value
     */
    public ApplicationArtifactName name() {
        return this.name;
    }

    /**
     * Set the managed application artifact name. Possible values include: 'NotSpecified', 'ViewDefinition', 'Authorizations', 'CustomRoleDefinition'.
     *
     * @param name the name value to set
     * @return the ApplicationArtifact object itself.
     */
    public ApplicationArtifact withName(ApplicationArtifactName name) {
        this.name = name;
        return this;
    }

    /**
     * Get the managed application artifact blob uri.
     *
     * @return the uri value
     */
    public String uri() {
        return this.uri;
    }

    /**
     * Set the managed application artifact blob uri.
     *
     * @param uri the uri value to set
     * @return the ApplicationArtifact object itself.
     */
    public ApplicationArtifact withUri(String uri) {
        this.uri = uri;
        return this;
    }

    /**
     * Get the managed application artifact type. Possible values include: 'NotSpecified', 'Template', 'Custom'.
     *
     * @return the type value
     */
    public ApplicationArtifactType type() {
        return this.type;
    }

    /**
     * Set the managed application artifact type. Possible values include: 'NotSpecified', 'Template', 'Custom'.
     *
     * @param type the type value to set
     * @return the ApplicationArtifact object itself.
     */
    public ApplicationArtifact withType(ApplicationArtifactType type) {
        this.type = type;
        return this;
    }

}