/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 *
 * Code generated by Microsoft (R) AutoRest Code Generator.
 */

package com.microsoft.azure.management.databox.v2019_09_01;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Operation display.
 */
public class OperationDisplay {
    /**
     * Provider name.
     */
    @JsonProperty(value = "provider")
    private String provider;

    /**
     * Resource name.
     */
    @JsonProperty(value = "resource")
    private String resource;

    /**
     * Localized name of the operation for display purpose.
     */
    @JsonProperty(value = "operation")
    private String operation;

    /**
     * Localized description of the operation for display purpose.
     */
    @JsonProperty(value = "description")
    private String description;

    /**
     * Get provider name.
     *
     * @return the provider value
     */
    public String provider() {
        return this.provider;
    }

    /**
     * Set provider name.
     *
     * @param provider the provider value to set
     * @return the OperationDisplay object itself.
     */
    public OperationDisplay withProvider(String provider) {
        this.provider = provider;
        return this;
    }

    /**
     * Get resource name.
     *
     * @return the resource value
     */
    public String resource() {
        return this.resource;
    }

    /**
     * Set resource name.
     *
     * @param resource the resource value to set
     * @return the OperationDisplay object itself.
     */
    public OperationDisplay withResource(String resource) {
        this.resource = resource;
        return this;
    }

    /**
     * Get localized name of the operation for display purpose.
     *
     * @return the operation value
     */
    public String operation() {
        return this.operation;
    }

    /**
     * Set localized name of the operation for display purpose.
     *
     * @param operation the operation value to set
     * @return the OperationDisplay object itself.
     */
    public OperationDisplay withOperation(String operation) {
        this.operation = operation;
        return this;
    }

    /**
     * Get localized description of the operation for display purpose.
     *
     * @return the description value
     */
    public String description() {
        return this.description;
    }

    /**
     * Set localized description of the operation for display purpose.
     *
     * @param description the description value to set
     * @return the OperationDisplay object itself.
     */
    public OperationDisplay withDescription(String description) {
        this.description = description;
        return this;
    }

}
