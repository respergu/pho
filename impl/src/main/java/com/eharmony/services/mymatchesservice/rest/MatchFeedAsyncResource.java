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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Resource;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
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

import com.codahale.metrics.annotation.Timed;
import com.eharmony.services.mymatchesservice.event.EventConstant;
import com.eharmony.services.mymatchesservice.service.MatchStatusGroupResolver;
import com.eharmony.singles.common.status.MatchStatus;
import com.google.common.collect.ImmutableSet;

@Component
@Path("/v1")
public class MatchFeedAsyncResource {

    private final Logger log = LoggerFactory.getLogger(this.getClass());
    
    private Set<String> ALL = ImmutableSet.of("all");

    @Resource
    private MatchCountAsyncRequestHandler countRequesthandler;

    private static final int TEASER_MATCH_DEFAULT_PAGINATION_SIZE = 1;
    private static final int TEASER_MATCH_DEFAULT_RESULT_SIZE = 5;
    private static final String COMM_MATCH_STATUS = "COMM";
    
    @Resource(name="userMyMatchesFeedAsyncRequestHandler")
    private MatchesFeedAsyncRequestHandler userMyMatchesFeedAsyncRequestHandler;
    
    @Resource(name="userTeaserMatchesFeedAsyncRequestHandler")
    private MatchesFeedAsyncRequestHandler userTeaserMatchesFeedAsyncRequestHandler;
    
    @Resource(name="userSortedMatchObjectsListAsyncRequestHandler")
    private MatchesFeedAsyncRequestHandler userSortedMatchObjectsListAsyncRequestHandler;

    @Resource(name="userSingleMatchAsyncRequestHandler")
    private UserSingleMatchAsyncRequestHandler userSingleMatchAsyncRequestHandler;

    
    @GET
    @Path("/users/{userId}/matches/{matchId}")
    @Produces(MediaType.APPLICATION_JSON) 
    @Timed(name="getMatch")
    public void getMatch(   @PathParam("userId") long userId, 
    						@PathParam("matchId") long matchId,
                            @MatrixParam("disabledSources") String sources,
    						@Suspended final AsyncResponse asyncResponse){
    	
    	SingleMatchQueryContext queryContext = new SingleMatchQueryContext();
    	queryContext.setMatchId(matchId)
    				.setUserId(userId)
    				.setDisabledSources(sources);
System.err.println("[MatchFeedAsyncResource] - Request disabledSources:" + queryContext.getDisabledSources());
        log.info("fetching single match for userId {} matchId {}", userId, matchId);
        userSingleMatchAsyncRequestHandler.getSingleMatch(queryContext, asyncResponse);  	 
    }

    
    
    @GET
    @Path("/users/{userId}/matches")
    @Produces(MediaType.APPLICATION_JSON)
    public void getMatches(@PathParam("userId") long userId, @MatrixParam("locale") String locale,
            @MatrixParam("status") Set<String> statuses, @QueryParam("viewHidden") boolean viewHidden,
            @QueryParam("allowedSeePhotos") boolean allowedSeePhotos, @QueryParam("pageNum") Integer pageNum,
            @QueryParam("pageSize") Integer pageSize, @Suspended final AsyncResponse asyncResponse){

        validateMatchFeedRequest(statuses, locale);
        
        Set<String> normalizedStatuses = toLowerCase(statuses);
        
        if(normalizedStatuses.contains("all")){
        	
        	MatchStatusGroupResolver.populateSetWithAllStatuses(normalizedStatuses);
        }
        
        int pn = (pageNum == null ? 0 : pageNum.intValue());
        int ps = (pageSize == null ? 0 : pageSize.intValue());

        MatchFeedQueryContext requestContext = MatchFeedQueryContextBuilder.newInstance()
                .setAllowedSeePhotos(allowedSeePhotos).setLocale(locale).setPageSize(ps).setStartPage(pn)
                .setStatuses(normalizedStatuses).setUserId(userId).setViewHidden(viewHidden)
                .build();

        log.info("fetching match feed for user ={}", userId);
        userMyMatchesFeedAsyncRequestHandler.getMatchesFeed(requestContext, asyncResponse);
    }


    /**
     * Returns matches with photos and with match status not in (archived or closed) or passed via 'status' param. The matches are sorted in the order of their 
     * score returned from the scorer service. 
     * 
     * WARNING: Feed no longer includes the user's gender and locale, those fields have been deprecated. 
     *  
     * @param userId  Id of the logged in user
     * @param statuses  set of match status values. Valid values none or one or both of [ 'new' , 'comm'].
     * @param resultSize  number of matches to be returned
     * @param asyncResponse Asynchronous response stream
     */
    @GET
    @Path("/users/{userId}/teasermatches")
    @Produces(MediaType.APPLICATION_JSON)
    public void getTeaserMatches(
    		@PathParam("userId") long userId, 
    		@MatrixParam("status") Set<String> statuses,
            @QueryParam("resultSize") Integer resultSize, 
            @HeaderParam("user-agent") String userAgent,
            @HeaderParam(EventConstant.PLATFORM) String platform,
            @Suspended final AsyncResponse asyncResponse) {

        log.debug("fetching teaser match feed for user ={}", userId);
        
        Set<String> statusSet = buildNormalizedMatchStatusSet(statuses);
       
        if (resultSize != null && resultSize <= 0) {
            throw new WebApplicationException("Invalid resultSize value", Status.BAD_REQUEST);
        }

        resultSize = (resultSize == null ? TEASER_MATCH_DEFAULT_RESULT_SIZE : resultSize.intValue());  //Setting the default result size to 5.
        
        Map<String,String> eventContextInfo = new HashMap<String,String>();
        if(StringUtils.isNotBlank(userAgent)){
            eventContextInfo.put(EventConstant.USER_AGENT, userAgent);
        }
        
        if(StringUtils.isNotBlank(platform)){
            eventContextInfo.put(EventConstant.PLATFORM, platform);
        }

        MatchFeedQueryContext requestContext = MatchFeedQueryContextBuilder.newInstance()
                .setAllowedSeePhotos(true)
                .setPageSize(0)   // Defaulting to Zero. By doing this teaser will by default load 1000 matches just like getMatches. This is also not used by teaser pagination.
                .setStartPage(TEASER_MATCH_DEFAULT_PAGINATION_SIZE)  //There will be no pagination. There will be only one page and the resultSize param will decide how many items it consists of.
                .setStatuses(statusSet)
                .setUserId(userId)
                .setTeaserResultSize(resultSize)        // This is the number of results to be returned back to the client/user.
                .setRequestMetadata(eventContextInfo)
                .setExcludeClosedMatches(true)		// Always exclude closed matches from Teaser result
                .build();

        userTeaserMatchesFeedAsyncRequestHandler.getMatchesFeed(requestContext, asyncResponse);
    }

    private Set<String> buildNormalizedMatchStatusSet(Set<String> statuses) {
        Set<String> statusSet = new HashSet<String>();
        if (!CollectionUtils.isEmpty(statuses)) {

            statuses.forEach(status -> {

                if (status.equalsIgnoreCase(MatchStatus.NEW.name())) {

                    statusSet.add(MatchStatus.NEW.name().toLowerCase());

                } else if (status.equalsIgnoreCase(COMM_MATCH_STATUS)) {

                    statusSet.add(MatchStatus.MYTURN.name().toLowerCase(Locale.US));
                    statusSet.add(MatchStatus.OPENCOMM.name().toLowerCase(Locale.US));
                    statusSet.add(MatchStatus.THEIRTURN.name().toLowerCase(Locale.US));

                }
            });

            if (CollectionUtils.isEmpty(statusSet)) {
                throw new WebApplicationException("Invalid status code sent. Valid value set are 'new', 'comm'", Status.BAD_REQUEST);
            }

        } else {

            //By default the search pool will include only the new matches.
            statusSet.add(MatchStatus.NEW.name().toLowerCase());

        }
        
        return statusSet;
    }
    private void validateMatchFeedRequest(Set<String> statuses, String locale) {
        //TODO remove this check and assume user requesting all matches if this field is empty
        if(CollectionUtils.isEmpty(statuses)){
            throw new WebApplicationException("Missing status.", Status.BAD_REQUEST);
        }
        if(StringUtils.isEmpty(locale)){
            throw new WebApplicationException("Missing locale.", Status.BAD_REQUEST);
        }
    }

    @GET
    @Path("/users/{userId}/matchedusers")
    @Produces(MediaType.APPLICATION_JSON)
    @Timed(name="getSimpleMatchedUserList")
    public void getSimpleMatchedUserList(@PathParam("userId") long userId, @MatrixParam("locale") String locale,
            @MatrixParam("status") Set<String> statuses, @QueryParam("viewHidden") boolean viewHidden, @QueryParam("sortBy") String sortBy,
            @DefaultValue("false") @QueryParam("excludeClosed") boolean excludeClosedMatches,
            @Suspended final AsyncResponse asyncResponse) {
        
        if (CollectionUtils.isEmpty(statuses)) {
            statuses = ALL;
        } else {
            statuses = toLowerCase(statuses);
        }

        MatchFeedQueryContext requestContext = MatchFeedQueryContextBuilder.newInstance().setAllowedSeePhotos(true)
                .setLocale(locale).setPageSize(0).setStartPage(0).setStatuses(statuses).setUserId(userId)
                .setViewHidden(false)
                .setExcludeClosedMatches(excludeClosedMatches)
                .addOrderBy(sortBy).build();


        log.info("fetching matched users for user ={}", userId);
        userSortedMatchObjectsListAsyncRequestHandler.getMatchesFeed(requestContext, asyncResponse);
    }
    
    @GET
    @Path("/users/{userId}/count")
    @Produces(MediaType.APPLICATION_JSON)
    @Timed(name="getMatchesCount")
    public void getMatchesCount(@PathParam("userId") long userId,
            @Suspended final AsyncResponse asyncResponse) {
        MatchCountRequestContext request = new MatchCountRequestContext(userId);
        countRequesthandler.getMatchesCount(request, asyncResponse);
    }

    private Set<String> toLowerCase(Set<String> values) {

        if (CollectionUtils.isEmpty(values)) {
            return null;
        }
        return values
                .stream()
                .map(strValue -> strValue.toLowerCase())
                .collect(Collectors.toSet());

    }
}
