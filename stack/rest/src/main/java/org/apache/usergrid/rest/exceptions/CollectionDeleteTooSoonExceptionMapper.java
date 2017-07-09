/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.usergrid.rest.exceptions;


import org.apache.usergrid.corepersistence.asyncevents.CollectionDeleteTooSoonException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;


@Provider
public class CollectionDeleteTooSoonExceptionMapper extends AbstractExceptionMapper<CollectionDeleteTooSoonException> {

    private static final Logger logger = LoggerFactory.getLogger(CollectionDeleteTooSoonExceptionMapper.class);

    @Override
    public Response toResponse( CollectionDeleteTooSoonException e ) {

        if(logger.isTraceEnabled()) {
            logger.trace("Tried to delete collection too soon after previous deletion", e.getMessage());
        }

        return toResponse( BAD_REQUEST, e );
    }
}