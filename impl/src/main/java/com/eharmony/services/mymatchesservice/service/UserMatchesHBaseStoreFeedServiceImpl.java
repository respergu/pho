package com.eharmony.services.mymatchesservice.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import rx.Observable;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;
import com.eharmony.configuration.Configuration;
import com.eharmony.datastore.model.MatchDataFeedItemDto;
import com.eharmony.datastore.repository.MatchDataFeedItemQueryRequest;
import com.eharmony.datastore.repository.MatchDataFeedQueryRequest;
import com.eharmony.datastore.repository.MatchStoreQueryRepository;
import com.eharmony.datastore.repository.MatchStoreSaveRepository;
import com.eharmony.services.mymatchesservice.monitoring.GraphiteReportingConfiguration;
import com.eharmony.services.mymatchesservice.monitoring.MatchQueryMetricsFactroy;
import com.eharmony.services.mymatchesservice.rest.MatchFeedQueryContext;
import com.eharmony.services.mymatchesservice.rest.MatchFeedRequestContext;
import com.eharmony.services.mymatchesservice.util.MatchStatusEnum;
import com.eharmony.services.mymatchesservice.util.MatchStatusGroupEnum;
import com.google.common.collect.Sets;

@Service
public class UserMatchesHBaseStoreFeedServiceImpl implements UserMatchesHBaseStoreFeedService {

    private static final Logger logger = LoggerFactory.getLogger(UserMatchesHBaseStoreFeedServiceImpl.class);

    @Resource
    private MatchStoreQueryRepository queryRepository;

    @Resource
    private MatchStoreSaveRepository saveRepository;

    @Resource
    private Configuration config;
    
    @Resource
    private MatchQueryMetricsFactroy matchQueryMetricsFactroy;

    @Value("${hbase.feed.parallel.fetch.enabled:true}")
    private boolean feedParallelFetchEnabled;

    @Resource(name = "matchFeedProfileFieldsList")
    private List<String> selectedProfileFields;

    @Resource
    private MatchFeedLimitsByStatusConfiguration matchFeedLimitsByStatusConfiguration;
    
    @Resource
    private MatchStatusGroupResolver matchStatusGroupResolver;

    private static final String ALL_MATCH_STATUS = "ALL";
    
    private static final String METRICS_HIERARCHY_PREFIX = UserMatchesHBaseStoreFeedServiceImpl.class.getCanonicalName();
    
    private static final String METRICS_GET_HBASE_PARALLEL = "getMatchesFromHBaseParallel";

    @Override
    public List<MatchDataFeedItemDto> getUserMatchesInternal(long userId) {
        MatchDataFeedQueryRequest request = new MatchDataFeedQueryRequest(userId);
        try {
            Set<MatchDataFeedItemDto> matchDataFeeditems = queryRepository.getMatchDataFeed(request);
            if (CollectionUtils.isNotEmpty(matchDataFeeditems)) {
                logger.debug("found {} matches for user {}", matchDataFeeditems.size(), userId);
                return new ArrayList<MatchDataFeedItemDto>(matchDataFeeditems);
            }
        } catch (Exception ex) {
            logger.warn("exception while fetching matches", ex);
            throw new RuntimeException(ex);
        }
        logger.debug("no matches found  for user {}", userId);
        return new ArrayList<MatchDataFeedItemDto>();
    }

    @Override
    public MatchDataFeedItemDto getUserMatch(long userId, long matchId) {
        MatchDataFeedItemQueryRequest request = new MatchDataFeedItemQueryRequest(userId);
        request.setMatchId(matchId);
        try {
            MatchDataFeedItemDto matchDataFeeditem = queryRepository.getMatchDataFeedItemDto(request);
            if (matchDataFeeditem != null) {
                logger.debug("found match for user {} and matchid {}", userId, matchId);
                return matchDataFeeditem;
            }
        } catch (Exception ex) {
            logger.warn("exception while fetching match", ex);
            throw new RuntimeException(ex);
        }
        return null;
    }

    @Override
    public Observable<Set<MatchDataFeedItemDto>> getUserMatchesFromHBaseStoreSafe(MatchFeedRequestContext requestContext) {
        Observable<Set<MatchDataFeedItemDto>> hbaseStoreFeed = Observable.defer(() -> Observable
                .just(getMatchesFeed(requestContext)));
        hbaseStoreFeed.onErrorReturn(ex -> {
            logger.warn("Exception while fetching data from hbase for user {} and returning empty set for safe method",
                    requestContext.getUserId(), ex);
            return Sets.newHashSet();
        });
        return hbaseStoreFeed;
    }

    private Set<MatchDataFeedItemDto> getMatchesFeed(MatchFeedRequestContext request) {
        if (feedParallelFetchEnabled) {
            return fetchMatchesFeedInParallel(request);
        } else {
            return getMatchesFeedSync(request);
        }
    }

    @SuppressWarnings("resource")
    @Deprecated
    private Set<MatchDataFeedItemDto> getMatchesFeedSync(MatchFeedRequestContext request) {
        Timer.Context timerContext = GraphiteReportingConfiguration.getRegistry()
                .timer(getClass().getCanonicalName() + ".getMatchesFromHBaseSync").time();
        try {
            MatchDataFeedQueryRequest requestQuery = new MatchDataFeedQueryRequest(request.getUserId());
            populateWithQueryParams(request, requestQuery);
            Set<MatchDataFeedItemDto> matchdataFeed = queryRepository.getMatchDataFeed(requestQuery);
            return matchdataFeed;
        } catch (Exception e) {
            logger.warn("Exception while fetching the matches from HBase store in sync for user {}",
                    request.getUserId(), e);
            throw new RuntimeException(e);
        } finally {
            long duration = timerContext.stop();
            logger.info("Total time to get the feed from hbase in sync for user {} in sync is {} NS",
                    request.getUserId(), duration);
        }
    }

    @SuppressWarnings("resource")
    private Set<MatchDataFeedItemDto> fetchMatchesFeedInParallel(MatchFeedRequestContext request) {

        Timer.Context timerContext = matchQueryMetricsFactroy.getTimerContext(METRICS_HIERARCHY_PREFIX, METRICS_GET_HBASE_PARALLEL);
        Histogram matchHistogram = matchQueryMetricsFactroy.getHistogram(METRICS_HIERARCHY_PREFIX, METRICS_GET_HBASE_PARALLEL);
        try {
            MatchFeedQueryContext queryContext = request.getMatchFeedQueryContext();
            Map<MatchStatusGroupEnum, Set<MatchStatusEnum>> matchStatusGroups = matchStatusGroupResolver.buildMatchesStatusGroups(queryContext.getUserId(), queryContext.getStatuses());
            Set<MatchDataFeedItemDto> matchesFeedByStatus = new HashSet<MatchDataFeedItemDto>();
            //Map<MatchStatusGroupEnum, Set<MatchDataFeedItemDto>> feedItemsByStatusGroupMap = new HashMap<MatchStatusGroupEnum, Set<MatchDataFeedItemDto>>();
            if (MapUtils.isNotEmpty(matchStatusGroups)) {
                //Observable<Map<MatchStatusGroupEnum, Set<MatchDataFeedItemDto>>> feedObservable = Observable.just(feedItemsByStatusGroupMap);
                for (MatchStatusGroupEnum matchStatusGroup : matchStatusGroups.keySet()) {
                    // TODO parallel
                    matchesFeedByStatus.addAll(getMatchesFromHbaseByStatusGroup(request, matchStatusGroup,
                            matchStatusGroups.get(matchStatusGroup)));
                    
                    //feedObservable.zipWith(other, zipFunction)
                }
            }
            matchHistogram.update(matchesFeedByStatus.size());
            return matchesFeedByStatus;
        } catch (Exception e) {
            logger.warn("Exception while fetching the matches from HBase store in parallel for user {}",
                    request.getUserId(), e);
            throw new RuntimeException(e);
        } finally {
            long duration = timerContext.stop();
            logger.info("Total time to get the feed from hbase in parallel for user {}  is {} NS", request.getUserId(),
                    duration);
        }
    } 
    
    public Observable<Set<MatchDataFeedItemDto>> getUserMatchesObservableFromHBaseByGroup(MatchFeedRequestContext requestContext) {
        Observable<Set<MatchDataFeedItemDto>> hbaseStoreFeed = Observable.defer(() -> Observable
                .just(getMatchesFeed(requestContext)));
        hbaseStoreFeed.onErrorReturn(ex -> {
            logger.warn("Exception while fetching data from hbase for user {} and returning empty set for safe method",
                    requestContext.getUserId(), ex);
            return Sets.newHashSet();
        });
        return hbaseStoreFeed;
    }


    private Set<MatchDataFeedItemDto> getMatchesFromHbaseByStatusGroup(final MatchFeedRequestContext request,
            final MatchStatusGroupEnum matchStatusGroup, final Set<MatchStatusEnum> matchStuses) {
        try {
            MatchDataFeedQueryRequest requestQuery = new MatchDataFeedQueryRequest(request.getUserId());
            pupulateRequestWithQueryParams(request, matchStatusGroup, matchStuses, requestQuery);
            Set<MatchDataFeedItemDto> matchdataFeed = queryRepository.getMatchDataFeed(requestQuery);
            return matchdataFeed;
        } catch (Exception e) {
            logger.warn("Exception while fetching the matches from HBase store for user {} and group {}",
                    request.getUserId(), matchStatusGroup, e);
            throw new RuntimeException(e);
        }
    }

    private void pupulateRequestWithQueryParams(final MatchFeedRequestContext request,
            final MatchStatusGroupEnum matchStatusGroup, final Set<MatchStatusEnum> matchStuses,
            MatchDataFeedQueryRequest requestQuery) {

        List<Integer> statuses = new ArrayList<Integer>();
        if (CollectionUtils.isNotEmpty(matchStuses)) {
            for (MatchStatusEnum matchStatus : matchStuses) {
                statuses.add(matchStatus.toInt());
            }
            requestQuery.setMatchStatusFilters(statuses);
            pupulateQueryWithLimitParams(request, matchStatusGroup, requestQuery);

        }
    }

    private void pupulateQueryWithLimitParams(final MatchFeedRequestContext request,
            final MatchStatusGroupEnum matchStatusGroup, MatchDataFeedQueryRequest requestQuery) {
        Integer feedLimit = null;
        feedLimit = matchFeedLimitsByStatusConfiguration.getDefaultFeedLimitForGroup(matchStatusGroup);
        
        if (feedLimit != null) {
            requestQuery.setStartPage(1);
            requestQuery.setPageSize(feedLimit);
        }
    }

    @Deprecated
    private void populateWithQueryParams(MatchFeedRequestContext request, MatchDataFeedQueryRequest requestQuery) {
        Set<String> statuses = request.getMatchFeedQueryContext().getStatuses();
        List<Integer> matchStatuses = new ArrayList<Integer>();
        if (CollectionUtils.isNotEmpty(statuses)) {
            for (String status : statuses) {
                if (ALL_MATCH_STATUS.equalsIgnoreCase(status)) {
                    matchStatuses = new ArrayList<Integer>();
                    break;
                }
                MatchStatusEnum statusEnum = MatchStatusEnum.fromName(status);
                if (statusEnum != null) {
                    matchStatuses.add(statusEnum.toInt());
                }
            }
            if (CollectionUtils.isNotEmpty(matchStatuses)) {
                requestQuery.setMatchStatusFilters(matchStatuses);
            }
        }
    }

}
