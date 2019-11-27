/**
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 *
 * Code generated by Microsoft (R) AutoRest Code Generator.
 */

package com.microsoft.azure.management.cosmosdb.v2019_08_01_preview.implementation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.microsoft.rest.serializer.JsonFlatten;
import com.microsoft.azure.management.cosmosdb.v2019_08_01_preview.ARMResourceProperties;

/**
 * An Azure Cosmos DB userDefinedFunction.
 */
@JsonFlatten
public class SqlUserDefinedFunctionGetResultsInner extends ARMResourceProperties {
    /**
     * Name of the Cosmos DB SQL userDefinedFunction.
     */
    @JsonProperty(value = "properties.id", required = true)
    private String sqlUserDefinedFunctionGetResultsId;

    /**
     * Body of the User Defined Function.
     */
    @JsonProperty(value = "properties.body")
    private String body;

    /**
     * A system generated property. A unique identifier.
     */
    @JsonProperty(value = "properties._rid", access = JsonProperty.Access.WRITE_ONLY)
    private String _rid;

    /**
     * A system generated property that denotes the last updated timestamp of
     * the resource.
     */
    @JsonProperty(value = "properties._ts", access = JsonProperty.Access.WRITE_ONLY)
    private Object _ts;

    /**
     * A system generated property representing the resource etag required for
     * optimistic concurrency control.
     */
    @JsonProperty(value = "properties._etag", access = JsonProperty.Access.WRITE_ONLY)
    private String _etag;

    /**
     * Get name of the Cosmos DB SQL userDefinedFunction.
     *
     * @return the sqlUserDefinedFunctionGetResultsId value
     */
    public String sqlUserDefinedFunctionGetResultsId() {
        return this.sqlUserDefinedFunctionGetResultsId;
    }

    /**
     * Set name of the Cosmos DB SQL userDefinedFunction.
     *
     * @param sqlUserDefinedFunctionGetResultsId the sqlUserDefinedFunctionGetResultsId value to set
     * @return the SqlUserDefinedFunctionGetResultsInner object itself.
     */
    public SqlUserDefinedFunctionGetResultsInner withSqlUserDefinedFunctionGetResultsId(String sqlUserDefinedFunctionGetResultsId) {
        this.sqlUserDefinedFunctionGetResultsId = sqlUserDefinedFunctionGetResultsId;
        return this;
    }

    /**
     * Get body of the User Defined Function.
     *
     * @return the body value
     */
    public String body() {
        return this.body;
    }

    /**
     * Set body of the User Defined Function.
     *
     * @param body the body value to set
     * @return the SqlUserDefinedFunctionGetResultsInner object itself.
     */
    public SqlUserDefinedFunctionGetResultsInner withBody(String body) {
        this.body = body;
        return this;
    }

    /**
     * Get a system generated property. A unique identifier.
     *
     * @return the _rid value
     */
    public String _rid() {
        return this._rid;
    }

    /**
     * Get a system generated property that denotes the last updated timestamp of the resource.
     *
     * @return the _ts value
     */
    public Object _ts() {
        return this._ts;
    }

    /**
     * Get a system generated property representing the resource etag required for optimistic concurrency control.
     *
     * @return the _etag value
     */
    public String _etag() {
        return this._etag;
    }

}