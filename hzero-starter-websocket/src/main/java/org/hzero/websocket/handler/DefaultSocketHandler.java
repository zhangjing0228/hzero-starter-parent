package org.hzero.websocket.handler;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.provider.token.AccessTokenConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.*;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.sockjs.transport.session.WebSocketServerSockJsSession;

import io.choerodon.core.convertor.ApplicationContextHelper;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.oauth.CustomTokenConverter;
import io.choerodon.core.oauth.CustomUserDetails;

import org.hzero.websocket.config.WebSocketConfig;
import org.hzero.websocket.constant.WebSocketConstant;
import org.hzero.websocket.helper.SocketMessageHandler;
import org.hzero.websocket.redis.BrokerServerSessionRedis;
import org.hzero.websocket.redis.BrokerUserSessionRedis;
import org.hzero.websocket.redis.SessionUserRedis;
import org.hzero.websocket.registry.GroupSessionRegistry;
import org.hzero.websocket.registry.UserSessionRegistry;
import org.hzero.websocket.vo.ClientVO;
import org.hzero.websocket.vo.MsgVO;
import org.hzero.websocket.vo.UserVO;

/**
 * 默认处理器
 *
 * @author shuangfei.zhu@hand-china.com 2020/04/28 16:46
 */
@Component
public class DefaultSocketHandler implements SocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSocketHandler.class);

    private final WebSocketConfig config;
    private final ObjectMapper objectMapper;

    @Autowired
    public DefaultSocketHandler(WebSocketConfig config,
                                ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public String processor() {
        return WebSocketConstant.DEFAULT_PROCESSOR;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        try {
            String sessionId = null;
            if (session instanceof StandardWebSocketSession) {
                // websocket连接方式
                sessionId = session.getId();
            } else if (session instanceof WebSocketServerSockJsSession) {
                // sock js 连接
                sessionId = ((WebSocketSession) FieldUtils.readField(session, "webSocketSession", true)).getId();
            } else {
                session.close();
            }
            Map<String, Object> attributeMap = session.getAttributes();
            if (attributeMap.containsKey(WebSocketConstant.Attributes.TOKEN)) {
                // 前端用户连接
                userConnection(session, sessionId);
            } else if (attributeMap.containsKey(WebSocketConstant.Attributes.SECRET_KEY) && attributeMap.containsKey(WebSocketConstant.Attributes.GROUP)) {
                // 密钥连接
                secretConnection(session, sessionId);
            } else {
                session.close();
            }

        } catch (Exception e) {
            try {
                session.close();
            } catch (IOException ex) {
                logger.debug("session : {} closed failed.", session);
            }
            logger.debug("webSocket connection failed. message : {}, token : {}", e.getMessage(), session.getAttributes().get(WebSocketConstant.Attributes.TOKEN));
        }
    }

    private final Lock scLock = new ReentrantLock();

    /**
     * 密钥链接
     *
     * @param session   WebSocketSession
     * @param sessionId WebSocketSessionId
     */
    private void secretConnection(WebSocketSession session, String sessionId) {
        String group = String.valueOf(session.getAttributes().get(WebSocketConstant.Attributes.GROUP));
        // 内存中存储webSocketSession
        GroupSessionRegistry.addSession(session, sessionId, group);
        // 记录Broker-serverSession
        String brokerId = GroupSessionRegistry.getBrokerId();
        try {
            scLock.tryLock(10, TimeUnit.SECONDS);
            BrokerServerSessionRedis.refreshCache(brokerId, group, new ClientVO(sessionId, group, brokerId));
        } catch (InterruptedException e) {
            logger.warn("InterruptedException occurred.", e);
        } finally {
            scLock.unlock();
        }
    }

    private final Lock ucLock = new ReentrantLock();

    /**
     * 用户连接
     *
     * @param session   WebSocketSession
     * @param sessionId WebSocketSessionId
     */
    private void userConnection(WebSocketSession session, String sessionId) {
        // 获取用户信息
        String token = String.valueOf(session.getAttributes().get(WebSocketConstant.Attributes.TOKEN));
        CustomUserDetails customUserDetails = getAuthentication(token, config.getOauthUrl());
        if (customUserDetails == null) {
            throw new CommonException("User authentication failed");
        }
        Long tenantId = customUserDetails.getTenantId();
        Long userId = customUserDetails.getUserId();
        Long roleId = customUserDetails.getRoleId();
        String brokerId = UserSessionRegistry.getBrokerId();
        UserVO user = new UserVO(sessionId, tenantId, roleId, token, brokerId);
        logger.debug("connection success. userId : {}", userId);

        try {
            ucLock.tryLock(10, TimeUnit.SECONDS);
            // 内存中存储webSocketSession
            UserSessionRegistry.addSession(session, sessionId, userId);
            // 记录Broker-userSession
            BrokerUserSessionRedis.refreshCache(brokerId, userId, user);
            // 记录Session-User
            SessionUserRedis.refreshCache(user);
        } catch (InterruptedException e) {
            logger.warn("InterruptedException occurred.", e);
        } finally {
            ucLock.unlock();
        }
    }

    /**
     * 认证用户
     *
     * @param token token
     * @return 认证信息
     */
    public static CustomUserDetails getAuthentication(String token, String oauthUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "bearer " + token);
        HttpEntity<String> entity = new HttpEntity<>("parameters", headers);
        RestTemplate restTemplate = (RestTemplate) ApplicationContextHelper.getContext().getBean("restTemplate");
        ResponseEntity<Map<String, Object>> responseEntity = restTemplate.exchange(oauthUrl + "?access_token=" + token, HttpMethod.GET, entity, new ParameterizedTypeReference<Map<String, Object>>() {
        });
        Map<String, Object> result = responseEntity.getBody();
        AccessTokenConverter tokenConverter = new CustomTokenConverter();
        Authentication authentication = tokenConverter.extractAuthentication(result);
        Object object = authentication.getDetails();
        if (object instanceof CustomUserDetails) {
            return (CustomUserDetails) object;
        } else {
            return null;
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {

    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {

    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String messageStr = message.getPayload();
        // 心跳
        if (Objects.equals(messageStr, config.getHeartbeat())) {
            return;
        }
        Map<String, SocketMessageHandler> beansOfType = ApplicationContextHelper.getContext().getBeansOfType(SocketMessageHandler.class);
        beansOfType.forEach((name, bean) -> {
            if (bean.needPrincipal() && session.getAttributes().containsKey(WebSocketConstant.Attributes.TOKEN)) {
                String token = String.valueOf(session.getAttributes().get(WebSocketConstant.Attributes.TOKEN));
                bean.setCustomUserDetails(DefaultSocketHandler.getAuthentication(token, config.getOauthUrl()));
            }
            MsgVO msg;
            try {
                msg = objectMapper.readValue(messageStr, MsgVO.class);
                bean.processMessage(msg);
            } catch (Exception e) {
                logger.error("handleMessage error , message : {}", message.getPayload());
            } finally {
                bean.clearCustomUserDetails();
            }
        });
    }

    @Override
    public void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        byte[] data = message.getPayload().array();
        Map<String, SocketMessageHandler> beansOfType = ApplicationContextHelper.getContext().getBeansOfType(SocketMessageHandler.class);
        beansOfType.forEach((name, bean) -> {
            if (bean.needPrincipal() && session.getAttributes().containsKey(WebSocketConstant.Attributes.TOKEN)) {
                String token = String.valueOf(session.getAttributes().get(WebSocketConstant.Attributes.TOKEN));
                bean.setCustomUserDetails(DefaultSocketHandler.getAuthentication(token, config.getOauthUrl()));
            }
            try {
                bean.processByte(session.getAttributes(), data);
            } catch (Exception e) {
                logger.error("handleMessage error , message : {}", message.getPayload());
            } finally {
                bean.clearCustomUserDetails();
            }
        });
    }

    @Override
    public void handlePongMessage(WebSocketSession session, PongMessage message) throws Exception {
        // TODO 待添加支持
        logger.info("Unexpected WebSocket message type: {}", message);
    }
}
