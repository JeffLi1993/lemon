package com.mossle.internal.sendmail.service;

import java.io.PrintWriter;
import java.io.StringWriter;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import javax.servlet.http.HttpServletResponse;

import com.mossle.api.store.StoreConnector;
import com.mossle.api.store.StoreDTO;

import com.mossle.core.hibernate.PropertyFilter;
import com.mossle.core.mapper.BeanMapper;
import com.mossle.core.mapper.JsonMapper;
import com.mossle.core.page.Page;
import com.mossle.core.spring.MessageHelper;
import com.mossle.core.util.StringUtils;

import com.mossle.ext.export.Exportor;
import com.mossle.ext.export.TableModel;
import com.mossle.ext.mail.MailDTO;
import com.mossle.ext.mail.MailHelper;
import com.mossle.ext.mail.MailServerInfo;
import com.mossle.ext.store.DataSourceInputStreamSource;
import com.mossle.ext.template.TemplateService;

import com.mossle.internal.sendmail.persistence.domain.SendmailAttachment;
import com.mossle.internal.sendmail.persistence.domain.SendmailConfig;
import com.mossle.internal.sendmail.persistence.domain.SendmailHistory;
import com.mossle.internal.sendmail.persistence.domain.SendmailQueue;
import com.mossle.internal.sendmail.persistence.domain.SendmailTemplate;
import com.mossle.internal.sendmail.persistence.manager.SendmailAttachmentManager;
import com.mossle.internal.sendmail.persistence.manager.SendmailConfigManager;
import com.mossle.internal.sendmail.persistence.manager.SendmailHistoryManager;
import com.mossle.internal.sendmail.persistence.manager.SendmailQueueManager;
import com.mossle.internal.sendmail.persistence.manager.SendmailTemplateManager;

import org.springframework.core.io.FileSystemResource;

import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import org.springframework.ui.Model;

import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Service
@Transactional
public class SendmailDataService {
    private SendmailConfigManager sendmailConfigManager;
    private SendmailQueueManager sendmailQueueManager;
    private SendmailHistoryManager sendmailHistoryManager;
    private SendmailTemplateManager sendmailTemplateManager;
    private StoreConnector storeConnector;
    private BeanMapper beanMapper = new BeanMapper();
    private MailHelper mailHelper;
    private TemplateService templateService;
    private JsonMapper jsonMapper = new JsonMapper();

    public void send(String to, String subject, String content,
            String configCode) {
        SendmailQueue sendmailQueue = new SendmailQueue();

        sendmailQueue.setReceiver(to);
        sendmailQueue.setSubject(subject);
        sendmailQueue.setContent(content);
        sendmailQueue.setSendmailConfig(sendmailConfigManager.findUniqueBy(
                "name", configCode));
        sendmailQueueManager.save(sendmailQueue);
    }

    public void sendTemplate(String to, String data, String templateCode,
            String configCode) {
        SendmailQueue sendmailQueue = new SendmailQueue();
        sendmailQueue.setReceiver(to);
        sendmailQueue.setData(data);
        sendmailQueue.setSendmailTemplate(sendmailTemplateManager.findUniqueBy(
                "name", templateCode));
        sendmailQueue.setSendmailConfig(sendmailConfigManager.findUniqueBy(
                "name", configCode));
        sendmailQueueManager.save(sendmailQueue);
    }

    public void saveSendmailQueue(String from, String to, String subject,
            String content, long sendmailConfigId) {
        SendmailConfig sendmailConfig = sendmailConfigManager
                .get(sendmailConfigId);
        SendmailQueue sendmailQueue = new SendmailQueue();
        sendmailQueue.setSender(from);
        sendmailQueue.setReceiver(to);
        sendmailQueue.setSubject(subject);
        sendmailQueue.setContent(content);
        sendmailQueue.setSendmailConfig(sendmailConfig);
        sendmailQueueManager.save(sendmailQueue);
    }

    public void saveSendmailQueue(String from, String to, String subject,
            String content) {
        SendmailQueue sendmailQueue = new SendmailQueue();
        sendmailQueue.setSender(from);
        sendmailQueue.setReceiver(to);
        sendmailQueue.setSubject(subject);
        sendmailQueue.setContent(content);
        sendmailQueueManager.save(sendmailQueue);
    }

    public List<SendmailQueue> findTopSendmailQueues(int size) {
        return (List<SendmailQueue>) sendmailQueueManager.pagedQuery(
                "from SendmailQueue", 1, size).getResult();
    }

    public void processSendmailQueue(SendmailQueue sendmailQueue)
            throws Exception {
        MailDTO mailDto = new MailDTO();
        MailDTO resultMailDto = null;

        if (sendmailQueue.getSendmailConfig() != null) {
            SendmailTemplate sendmailTemplate = sendmailQueue
                    .getSendmailTemplate();
            SendmailConfig sendmailConfig = sendmailQueue.getSendmailConfig();

            MailServerInfo mailServerInfo = new MailServerInfo();
            mailServerInfo.setHost(sendmailConfig.getHost());
            mailServerInfo.setSmtpAuth(sendmailConfig.getSmtpAuth() == 1);
            mailServerInfo
                    .setSmtpStarttls(sendmailConfig.getSmtpStarttls() == 1);
            mailServerInfo.setUsername(sendmailConfig.getUsername());
            mailServerInfo.setPassword(sendmailConfig.getPassword());
            mailServerInfo.setDefaultFrom(sendmailConfig.getDefaultFrom());
            mailServerInfo.setMode(sendmailConfig.getStatus());
            mailServerInfo.setTestMail(sendmailConfig.getTestMail());

            // config
            this.configSender(mailDto, sendmailQueue, sendmailTemplate);
            this.configReceiver(mailDto, sendmailQueue, sendmailTemplate);
            this.configCc(mailDto, sendmailQueue, sendmailTemplate);
            this.configBcc(mailDto, sendmailQueue, sendmailTemplate);

            if (StringUtils.isBlank(sendmailQueue.getSubject())) {
                if (StringUtils.isBlank(sendmailQueue.getData())) {
                    mailDto.setSubject(sendmailTemplate.getSubject());
                } else {
                    Map<String, Object> map = jsonMapper.fromJson(
                            sendmailQueue.getData(), Map.class);
                    String subject = templateService.renderText(
                            sendmailTemplate.getSubject(), map);
                    mailDto.setSubject(subject);
                }
            } else {
                mailDto.setSubject(sendmailQueue.getSubject());
            }

            if (StringUtils.isBlank(sendmailQueue.getContent())) {
                if (StringUtils.isBlank(sendmailQueue.getData())) {
                    mailDto.setContent(sendmailTemplate.getContent());
                } else {
                    Map<String, Object> map = jsonMapper.fromJson(
                            sendmailQueue.getData(), Map.class);
                    String content = templateService.renderText(
                            sendmailTemplate.getContent(), map);
                    mailDto.setContent(content);
                }
            } else {
                mailDto.setContent(sendmailQueue.getContent());
            }

            // mailDto.setFrom(mailTemplate.getSender());
            // mailDto.setTo(mailTemplate.getReceiver());
            // mailDto.setCc(mailTemplate.getCc());
            // mailDto.setBcc(mailTemplate.getBcc());
            // mailDto.setSubject(mailTemplate.getSubject());
            // mailDto.setContent(mailTemplate.getContent());
            if (sendmailTemplate != null) {
                for (SendmailAttachment sendmailAttachment : sendmailTemplate
                        .getSendmailAttachments()) {
                    StoreDTO storeDto = storeConnector.getStore(
                            "sendmailattachment", sendmailAttachment.getPath());
                    mailDto.addAttachment(
                            sendmailAttachment.getName(),
                            new DataSourceInputStreamSource(storeDto
                                    .getDataSource()));
                }
            }

            resultMailDto = new MailHelper().send(mailDto, mailServerInfo);

            // model.addAttribute("mailDto", mailDto);
            if (!resultMailDto.isSuccess()) {
                StringWriter writer = new StringWriter();
                resultMailDto.getException().printStackTrace(
                        new PrintWriter(writer));

                // model.addAttribute("exception", writer.toString());
            }
        } else {
            mailDto.setFrom(sendmailQueue.getSender());
            mailDto.setTo(sendmailQueue.getReceiver());
            mailDto.setSubject(sendmailQueue.getSubject());
            mailDto.setContent(sendmailQueue.getContent());
            resultMailDto = mailHelper.send(mailDto);
        }

        this.saveMailHistory(sendmailQueue, resultMailDto);
    }

    public void configSender(MailDTO mailDto, SendmailQueue sendmailQueue,
            SendmailTemplate sendmailTemplate) {
        if (StringUtils.isBlank(sendmailQueue.getSender())) {
            if ((sendmailTemplate != null)
                    && (sendmailTemplate.getSender() != null)) {
                mailDto.setFrom(sendmailTemplate.getSender().trim());
            }
        } else {
            mailDto.setFrom(sendmailQueue.getSender().trim());
        }
    }

    public void configReceiver(MailDTO mailDto, SendmailQueue sendmailQueue,
            SendmailTemplate sendmailTemplate) {
        if (StringUtils.isBlank(sendmailQueue.getReceiver())) {
            if ((sendmailTemplate != null)
                    && (sendmailTemplate.getReceiver() != null)) {
                mailDto.setTo(sendmailTemplate.getReceiver().trim());
            }
        } else {
            mailDto.setTo(sendmailQueue.getReceiver().trim());
        }
    }

    public void configCc(MailDTO mailDto, SendmailQueue sendmailQueue,
            SendmailTemplate sendmailTemplate) {
        if (StringUtils.isBlank(sendmailQueue.getCc())) {
            if ((sendmailTemplate != null)
                    && (sendmailTemplate.getCc() != null)) {
                mailDto.setCc(sendmailTemplate.getCc().trim());
            }
        } else {
            mailDto.setCc(sendmailQueue.getCc().trim());
        }
    }

    public void configBcc(MailDTO mailDto, SendmailQueue sendmailQueue,
            SendmailTemplate sendmailTemplate) {
        if (StringUtils.isBlank(sendmailQueue.getBcc())) {
            if ((sendmailTemplate != null)
                    && (sendmailTemplate.getBcc() != null)) {
                mailDto.setBcc(sendmailTemplate.getBcc().trim());
            }
        } else {
            mailDto.setBcc(sendmailQueue.getBcc().trim());
        }
    }

    public void saveMailHistory(SendmailQueue sendmailQueue, MailDTO mailDto) {
        SendmailHistory sendmailHistory = new SendmailHistory();
        beanMapper.copy(mailDto, sendmailHistory);
        sendmailHistory.setSender(mailDto.getFrom());
        sendmailHistory.setReceiver(mailDto.getTo());
        sendmailHistory.setStatus(mailDto.isSuccess() ? "success" : "error");

        if (mailDto.getException() != null) {
            sendmailHistory.setInfo(mailDto.getException().getMessage());
        }

        sendmailHistory
                .setSendmailTemplate(sendmailQueue.getSendmailTemplate());
        sendmailHistory.setSendmailConfig(sendmailQueue.getSendmailConfig());
        sendmailHistory.setData(sendmailQueue.getData());
        sendmailHistory.setCreateTime(new Date());

        sendmailHistoryManager.save(sendmailHistory);
        sendmailQueueManager.remove(sendmailQueue);
    }

    public boolean checkConfigCodeExists(String configCode) {
        SendmailConfig sendmailConfig = sendmailConfigManager.findUniqueBy(
                "name", configCode);

        return sendmailConfig != null;
    }

    public boolean checkTemplateCodeExists(String templateCode) {
        SendmailTemplate sendmailTemplate = sendmailTemplateManager
                .findUniqueBy("name", templateCode);

        return sendmailTemplate != null;
    }

    @Resource
    public void setSendmailConfigManager(
            SendmailConfigManager sendmailConfigManager) {
        this.sendmailConfigManager = sendmailConfigManager;
    }

    @Resource
    public void setSendmailQueueManager(
            SendmailQueueManager sendmailQueueManager) {
        this.sendmailQueueManager = sendmailQueueManager;
    }

    @Resource
    public void setSendmailHistoryManager(
            SendmailHistoryManager sendmailHistoryManager) {
        this.sendmailHistoryManager = sendmailHistoryManager;
    }

    @Resource
    public void setSendmailTemplateManager(
            SendmailTemplateManager sendmailTemplateManager) {
        this.sendmailTemplateManager = sendmailTemplateManager;
    }

    @Resource
    public void setStoreConnector(StoreConnector storeConnector) {
        this.storeConnector = storeConnector;
    }

    @Resource
    public void setMailHelper(MailHelper mailHelper) {
        this.mailHelper = mailHelper;
    }

    @Resource
    public void setTemplateService(TemplateService templateService) {
        this.templateService = templateService;
    }
}
