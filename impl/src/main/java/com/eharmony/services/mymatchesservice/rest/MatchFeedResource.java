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

import java.util.List;

import javax.annotation.Resource;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.codahale.metrics.annotation.Timed;
import com.eharmony.datastore.model.MatchDataFeedItemDto;
import com.eharmony.services.mymatchesservice.service.UserMatchesFeedService;

@Component
@Path("/v1/internal")
public class MatchFeedResource {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    @Resource
    private UserMatchesFeedService userMatchesFeedService;

    @GET
    @Path("/users/{userId}/matches/{matchId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Timed
    public MatchDataFeedItemDto getMatch(@PathParam("userId") Integer userId, @PathParam("matchId") Long matchId) {

        log.info("fetching match for user ={} and match ={}", userId, matchId);

        return userMatchesFeedService.getUserMatch(userId, matchId);

    }

    @GET
    @Path("/users/{userId}/matches")
    @Produces(MediaType.APPLICATION_JSON)
    public List<MatchDataFeedItemDto> getMatchfeedInternal(@PathParam("userId") Integer userId) {

        log.info("fetching match feed for user ={}", userId);

        return userMatchesFeedService.getUserMatchesInternal(userId);

    }
    
}
