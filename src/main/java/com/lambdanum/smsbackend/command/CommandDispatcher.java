package com.lambdanum.smsbackend.command;

import com.lambdanum.smsbackend.command.tree.ConversationalTree;
import com.lambdanum.smsbackend.command.tree.DecisionNode;
import com.lambdanum.smsbackend.command.tree.ReservedKeyWordConverter;
import com.lambdanum.smsbackend.identity.User;
import com.lambdanum.smsbackend.identity.UserRoleEnum;
import com.lambdanum.smsbackend.messaging.Message;
import com.lambdanum.smsbackend.messaging.MessageProvider;
import com.lambdanum.smsbackend.nlp.StringHelper;
import com.lambdanum.smsbackend.nlp.TokenizerService;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

@Component
public class CommandDispatcher {

    private static Logger logger = LoggerFactory.getLogger(CommandDispatcher.class);

    private DecisionNode rootNode = DecisionNode.createRootNode();
    private HashMap<String, ReservedKeyWordConverter> tokenConverters = new HashMap<>();
    private TokenizerService tokenizerService;

    private Map<Class<?>, ConversationalTree> conversationalTrees = new HashMap<>();

    @Autowired
    public CommandDispatcher(ApplicationContext applicationContext, List<ReservedKeyWordConverter> tokenConverters, TokenizerService tokenizerService) {
        this.tokenizerService = tokenizerService;

        for (Object listener : applicationContext.getBeansWithAnnotation(CommandListener.class).values()) {
            conversationalTrees.put(listener.getClass(),new ConversationalTree());

            List<Method> methods = Arrays.asList(listener.getClass().getMethods());
            for (Method method : methods) {
                if (method.getAnnotation(CommandHandler.class) == null) {
                    continue;
                }
                Conversational conversationalAnnotation = method.getAnnotation(Conversational.class);
                if (conversationalAnnotation != null) {
                    String conversationTrigger = conversationalAnnotation.value();
                    Class<?> triggerClass = (conversationalAnnotation.targetClass() == String.class) ? listener.getClass() : conversationalAnnotation.targetClass();

                    DecisionNode conversationalRootNode = conversationalTrees.get(triggerClass).getOrCreateRootNodeForMethod(conversationTrigger);
                    registerHandlerMethod(conversationalRootNode, method, listener);
                } else {
                    registerHandlerMethod(rootNode, method, listener);
                }

            }

        }
        logger.info("Initialized command tree.");

        for (ReservedKeyWordConverter converter : tokenConverters) {
            if (this.tokenConverters.containsKey(converter.getMatchedToken())) {
                logger.error("Error initializing tokenConverters. Conflicting definitions found.");
                throw new IllegalStateException("Token " + converter.getMatchedToken() + " already defined.");
            }
            this.tokenConverters.put(converter.getMatchedToken(), converter);
        }

        logger.info("Initialized Token converters.");



    }

    public void registerHandlerMethod(DecisionNode rootNode, Method method, Object listener) {
        DecisionNode subTree = rootNode;

        for (String word : method.getAnnotation(CommandHandler.class).value().split(" ")) {
            if (!subTree.hasChild(word)) {
                subTree.registerChild(word,new DecisionNode());
            }
            subTree = subTree.getChild(word);
        }

        UserRoleEnum requiredPrivilege = method.getAnnotation(CommandHandler.class).requiredRole();
        MethodInvocationWrapper action = new MethodInvocationWrapper(listener, method);
        if (subTree.isExitNode()) {
            logger.error("Error initializing command tree. Multiple identical command definitions.");
            throw new IllegalStateException("Command '" + method.getAnnotation(CommandHandler.class).value() + "' already defined.");
        }
        subTree.setMethodInvocationWrapper(action);
        subTree.setRequiredRole(requiredPrivilege);
    }

    @Deprecated
    public Object executeCommand(Message incomingMessage, User user, MessageProvider messageProvider) {
        return executeCommand(incomingMessage.getContent(), new CommandContext(incomingMessage, user, messageProvider));
    }

    public Object executeCommand(String command) {
        CommandContext dummyContext = new CommandContext();
        User dummyUser = new User();
        dummyUser.setName("dummy");
        dummyUser.addRole(UserRoleEnum.INTRODUCED);
        dummyContext.setUser(dummyUser);
        return executeCommand(command, dummyContext);
    }

    public Object executeCommand(String command, CommandContext context) {
        command = StringHelper.spacePunctuation(command);
        List<String> tokens = tokenizerService.tokenizeAndStem(command);
        String[] tokenArray = tokens.toArray(new String[tokens.size()]);
        String[] referenceString = command.split(" ");
        if (tokens.size() != referenceString.length) {
            logger.warn("Stemmed tokens count different to referenceString word count. Using tokenized version only. Parameters might be altered.");
            try {
                return exploreConversationalTrees(tokenArray, tokenArray, context);
            } catch (UnknownCommandException e) {
                return explore(rootNode, tokenArray, tokenArray, context);
            }
        }
        try {
            return exploreConversationalTrees(tokenArray, referenceString, context);
        } catch (UnknownCommandException e) {
            return explore(rootNode, tokenArray, referenceString, context);
        }
    }

    private Object exploreConversationalTrees(String[] command, String [] reference, CommandContext context) {
        for (ConversationalCommand conversationalCommand : context.getConversationalCommands()) {
            try {
                return explore(conversationalCommand.getRootNode(), command, reference, context);
            } catch (UnknownCommandException e) { }
        }
        throw new UnknownCommandException();
    }

    private Object explore(DecisionNode subtree, String[] command, String[] reference, CommandContext context) {
        if (command.length == 0) {
            if (!subtree.isExitNode()) {
                throw new UnknownCommandException();
            }
            if (conversationalTrees.containsKey(subtree.getMethodClass()) && conversationalTrees.get(subtree.getMethodClass()).containsConversationalMethod(subtree.getMethodName())) {
                context.addConversationalCommand(conversationalTrees.get(subtree.getMethodClass()).getConversationalMethodTree(subtree.getMethodName()));
            }
            return subtree.invoke(context);
        }

        String nextWord = command[0];

        if (subtree.hasChild(nextWord)) {
            return explore(subtree.getChild(nextWord), ArrayUtils.subarray(command, 1, command.length), ArrayUtils.subarray(reference, 1, reference.length), context);
        }

        for (String child : subtree.getChildren()) {
            if (tokenConverters.containsKey(child)) {
                if (tokenConverters.get(child).isMatching(nextWord)) {
                    if(isStartOfArrayParameter(subtree.getChild(child))) {
                        List<Object> arrayParameter = getArrayParameterStartingHere(tokenConverters.get(child),subtree,reference);
                        context.addArgument(arrayParameter);
                        return explore(subtree.getChild(child).getChild("..."), ArrayUtils.subarray(command, arrayParameter.size(), command.length), ArrayUtils.subarray(reference, arrayParameter.size(), reference.length), context);

                    }
                    context.addArgument(tokenConverters.get(child).getMatchingObject(nextWord));
                    return explore(subtree.getChild(child), ArrayUtils.subarray(command, 1, command.length),ArrayUtils.subarray(reference, 1, reference.length), context);
                }
            }
        }

        return explore(subtree, ArrayUtils.subarray(command, 1, command.length), ArrayUtils.subarray(reference, 1, reference.length), context);
    }

    private List<Object> getArrayParameterStartingHere(ReservedKeyWordConverter converter, DecisionNode node, String[] command) {
        if (command.length == 0) {
            return Collections.emptyList();
        }
        List<Object> arrayParameter = new ArrayList<>();

        Set<String> exitWords = node.getChild(converter.getMatchedToken()).getChild("...").getChildren();
        Set<String> exitTokens = node.getChild(converter.getMatchedToken()).getChild("...").getReservedChildren();

        for (String word : command) {
            if (exitWords.contains(word) || !converter.isMatching(word) || setContainsMatcherToken(exitTokens, word)) {
                break;
            }
            arrayParameter.add(converter.getMatchingObject(word));
        }

        return arrayParameter;
    }

    private boolean setContainsMatcherToken(Set<String> tokens, String matchedWord) {
        return tokens.stream().anyMatch(token -> tokenConverters.get(token).isMatching(matchedWord));
    }

    private boolean isStartOfArrayParameter(DecisionNode node) {
        return node.hasChild("...");
    }
}
