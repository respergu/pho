/*
 * This software is the confidential and proprietary information of
 * eharmony.com and may not be used, reproduced, modified, distributed,
 * publicly displayed or otherwise disclosed without the express written
 * consent of eharmony.com.
 *
 * This software is a work of authorship by eharmony.com and protected by
 * the copyright laws of the United States and foreign jurisdictions.
 *
 * Copyright 2000-2015 eharmony.com, Inc. All rights reserved.
 *
 */
package com.eharmony.services.mymatchesservice.rest;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Resource;
import javax.ws.rs.GET;
import javax.ws.rs.MatrixParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.eharmony.services.mymatchesservice.rest.internal.DataServiceStateEnum;
import com.eharmony.singles.common.status.MatchStatus;

@Component
@Path("/v1")
public class MatchFeedAsyncResource {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Resource
    private MatchFeedAsyncRequestHandler requesthandler;

    private static final int ONE = 1;
    
    private static final int FIVE = 5;
    
    @GET
    @Path("/users/{userId}/matches")
    @Produces(MediaType.APPLICATION_JSON)
    public void getMatches(@PathParam("userId") long userId, @MatrixParam("locale") String locale,
            @MatrixParam("status") Set<String> statuses, @QueryParam("viewHidden") boolean viewHidden,
            @QueryParam("allowedSeePhotos") boolean allowedSeePhotos, @QueryParam("pageNum") Integer pageNum,
            @QueryParam("pageSize") Integer pageSize, @Suspended final AsyncResponse asyncResponse, 
            @QueryParam("voldyState") DataServiceStateEnum voldyState) {

        //TODO remove this check and assume user requesting all matches if this field is empty
    	if(CollectionUtils.isEmpty(statuses)){
            throw new WebApplicationException("Missing status.", Status.BAD_REQUEST);
    	}
    	
    	if(StringUtils.isEmpty(locale)){
            throw new WebApplicationException("Missing locale.", Status.BAD_REQUEST);
        }

        Set<String> normalizedStatuses = toLowerCase(statuses);
        int pn = (pageNum == null ? 0 : pageNum.intValue());
        int ps = (pageSize == null ? 0 : pageSize.intValue());

        MatchFeedQueryContext requestContext = MatchFeedQueryContextBuilder.newInstance()
                .setAllowedSeePhotos(allowedSeePhotos).setLocale(locale).setPageSize(ps).setStartPage(pn)
                .setStatuses(normalizedStatuses).setUserId(userId).setViewHidden(viewHidden)
                .setVoldyState(voldyState).build();

        log.info("fetching match feed for user ={}", userId);
        requesthandler.getMatchesFeed(requestContext, asyncResponse, false);
    }
    
    
    /**
     * Returns matches with photos and with match status not in (archived or closed) or passed via 'status' param. The matches are sorted in the order of their 
     * score returned from the scorer service. 
     *  
     * @param userId  Id of the logged in user
     * @param locale  Locale of the logged in user
     * @param viewHidden view hidden profiles
     * @param resultSize  number of matches to be returned
     * @param asyncResponse Asynchronous response stream
     */
    @GET
    @Path("/users/{userId}/teasermatches")
    @Produces(MediaType.APPLICATION_JSON)
    public void getTeaserMatches(
    		@PathParam("userId") long userId, 
    		@MatrixParam("locale") String locale,
            @QueryParam("viewHidden") boolean viewHidden,
            @QueryParam("resultSize") Integer resultSize, 
            @Suspended final AsyncResponse asyncResponse) {

    	if(StringUtils.isEmpty(locale)){
    		
            throw new WebApplicationException("Missing locale.", Status.BAD_REQUEST);
        }
    		
    	// Return matches which are in new or in comm.
    	Set<String>	statuses = new HashSet<String>();
    	statuses.add(MatchStatus.NEW.name().toLowerCase(Locale.US));
    	statuses.add(MatchStatus.MYTURN.name().toLowerCase(Locale.US));
    	statuses.add(MatchStatus.OPENCOMM.name().toLowerCase(Locale.US));
    	statuses.add(MatchStatus.THEIRTURN.name().toLowerCase(Locale.US));
       
        resultSize = (resultSize == null ? FIVE : resultSize.intValue());  //Setting the default result size to 5.

        MatchFeedQueryContext requestContext = MatchFeedQueryContextBuilder.newInstance()
                .setAllowedSeePhotos(true)
                .setLocale(locale)
                .setPageSize(resultSize)
                .setStartPage(ONE)  //There will be no pagination. There will be only one page and the resultSize param will decide how many items it consists of.
                .setStatuses(statuses)
                .setUserId(userId)
                .setViewHidden(viewHidden).build();

        log.debug("fetching teaser match feed for user ={}", userId);
        requesthandler.getMatchesFeed(requestContext, asyncResponse, true);
    }

    private Set<String> toLowerCase(Set<String> values) {

        if (CollectionUtils.isEmpty(values)) {
            return null;
        }
        
        Set<String> result = new HashSet<String>();
        for (String value : values) {
            result.add(value.toLowerCase());
        }
        return result;

    }
}
