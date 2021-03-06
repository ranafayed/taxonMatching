package com.bibalex.taxonmatcher.controllers;

import com.bibalex.taxonmatcher.handlers.*;
import com.bibalex.taxonmatcher.models.*;
import org.apache.logging.log4j.Logger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Created by Amr.Morad
 */
public class NodeMapper {

    private StrategyHandler strategyHandler;
    private GlobalNamesHandler globalNameHandler;
    private NodeHandler nodeHandler;
    private MatchingScoreHandler matchingScoreHandler;
    private SearchHandler searchHandler;
    private int maxAncestorDepth;
    private static Logger logger;
    private FileHandler fileHandler;
    private Neo4jHandler neo4jHandler;

    public NodeMapper(){
        strategyHandler = new StrategyHandler();
        globalNameHandler = new GlobalNamesHandler();
        nodeHandler = new NodeHandler();
        matchingScoreHandler = new MatchingScoreHandler();
        searchHandler = new SearchHandler();
        maxAncestorDepth = Integer.parseInt(ResourceHandler.getPropertyValue("maxAncestorDepth"));
        logger = LogHandler.getLogger(NodeMapper.class.getName());
        fileHandler = new FileHandler();
        neo4jHandler = new Neo4jHandler();
    }

    public void mapAllNodesToPages(ArrayList<Node> rootNodes){
        mapNodes(rootNodes);
    }

    public void mapNodes(ArrayList<Node> rootNodes){
        System.out.println("mapNodes");
        logger.info("Inside Map Node method");
        for(Node node : rootNodes){
            System.out.println("mapNodes: mapping node: " + node.getScientificName());
            logger.info("mapNodes: mapping node: " + node.getScientificName());
            mapIfNeeded(node);
        }
    }

    public void mapIfNeeded(Node node){
        Strategy usedStrategy = strategyHandler.defaultStrategy();
        System.out.println("mapIfNeeded: used strategy is: " + usedStrategy.getAttribute());
        logger.info("mapIfNeeded: used strategy is: " + usedStrategy.getAttribute());
        int usedAncestorDepth = 0;
        if (node.needsToBeMapped()){
            System.out.println("mapIfNeeded: needs to be mapped");
            logger.info("mapIfNeeded: needs to be mapped");
            if(!globalNameHandler.hasAuthority(node.getScientificName())){
                System.out.println("mapIfNeeded: node does not have authority");
                logger.info("mapIfNeeded: node does not have authority");
                usedStrategy = strategyHandler.firstNonScientificStrategy();
            }
            System.out.println("mapIfNeeded: before mapNode");
            logger.info("mapIfNeeded: before mapNode");
            mapNode(node, usedAncestorDepth, usedStrategy);
        }
        System.out.println("---------------- has children is ------------- " + node.hasChildren() + " size " + node.getChildren().size());
        if(node.hasChildren()){
            System.out.println("====================children=================");
            logger.info("====================children=================");
            mapNodes(node.getChildren());
        }
    }

    private void mapNode(Node node, int depth, Strategy strategy){
        Node ancestor;
        if(globalNameHandler.isSurrogate(node.getScientificName())){
            System.out.println("map node: surrogate");
            logger.info("map node: surrogate");
            unmappedNode(node);
        }else{
            if (globalNameHandler.isVirus(node.getScientificName())){
                System.out.println("map node: virus");
                logger.info("map node: virus");
                ancestor = nodeHandler.nativeVirus();
            }else{
                System.out.println("map Node : not virus neither surrogate");
                logger.info("map Node : not virus neither surrogate");
                ancestor = nodeHandler.matchedAncestor(node.getAnecstors(), depth);
            }
            mapUnflaggedNode(node, ancestor, depth, strategy);
        }
    }

    private void mapUnflaggedNode(Node node, Node ancestor, int depth, Strategy strategy){
        ArrayList<SearchResult> results = searchHandler.getResults(node, strategy, ancestor);
        Strategy nextStrategy;
        if(results.size() == 1){
            System.out.println("results returned is one");
            logger.info("results returned is one");
            mapToPage(node, results.get(0).getPageId());
        }else if(results.size() > 1){
            System.out.println("results returned is greater than one");
            logger.info("results returned is greater than one");
            mapToPage(node, findBestMatch(node, results));
        }else{
            nextStrategy = strategyHandler.getNextStrategy(strategy);
            if (nextStrategy == null) {
                nextStrategy = strategyHandler.firstNonScientificStrategy();
                depth++;
                System.out.println("depth is: " + depth);
                logger.info("depth is: " + depth);
                if (depth > maxAncestorDepth) {
                    System.out.println("depth is greater than max depth");
                    logger.info("depth is greater than max depth");
                    unmappedNode(node);
                    return;
                }
                System.out.println("depth is less than max depth and will call recursion");
                logger.info("depth is less than max depth and will call recursion");
            }
            System.out.println("Recursive call");
            logger.info("Recursive call");
            mapUnflaggedNode(node, ancestor, depth, nextStrategy);
        }
    }

    private int findBestMatch(Node node, ArrayList<SearchResult> results){
        ArrayList<MatchingScore> scores = new ArrayList<MatchingScore>();

        for(SearchResult result : results){
            int matchedChildrenCount = matchingScoreHandler.countMatches(neo4jHandler.getNodesFromIds(result.getChildren()),
                    node.getChildren());
            logger.info("matched children count " + matchedChildrenCount);
            int matchedAncestorsCount = matchingScoreHandler.countAncestors(node);
            logger.info("matched children count " + matchedChildrenCount);
            double overallScore = matchingScoreHandler.calculateScore(matchedChildrenCount, matchedAncestorsCount);
            logger.info("overall score: "+overallScore);
            MatchingScore score = new MatchingScore(matchedChildrenCount,
                    matchedAncestorsCount, overallScore, result.getPageId());
            logger.info("score: "+score.getScore() + " of page: "+score.getPageId());
            scores.add(score);
        }

        Collections.sort(scores, new Comparator<MatchingScore>(){
            public int compare(MatchingScore score1, MatchingScore score2)
            {
                return  Double.compare(score1.getScore(), score2.getScore());
            }
        });
        return scores.get(0).getPageId();
    }



    private void mapToPage(Node node, int pageId){
        neo4jHandler.assignPageToNode(node.getGeneratedNodeId(), pageId);
        System.out.println("Node with name " + node.getScientificName() + " is mapped to page "+node.getPageId());
        logger.info("Node with name " + node.getScientificName() + " is mapped to page "+node.getPageId());
        fileHandler.writeToFile("Node with name " + node.getScientificName() + " is mapped to page "+node.getPageId());
    }

    private void unmappedNode(Node node){
        System.out.println("New page is created for node named: "+node.getScientificName());
        logger.info("New page is created for node named: "+node.getScientificName());
        fileHandler.writeToFile("New page is created for node named: "+node.getScientificName());
        neo4jHandler.assignPageToNode(node.getGeneratedNodeId());
      //  Page newPage = new Page();
       // node.setPageId(newPage.getId());
    }

}
