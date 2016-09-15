/*
 * Copyright (c) 2012-2016, b3log.org & hacpai.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.b3log.symphony.util;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import java.io.File;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jodd.http.HttpRequest;
import jodd.http.HttpResponse;
import org.apache.commons.lang.StringUtils;
import org.b3log.latke.ioc.LatkeBeanManager;
import org.b3log.latke.ioc.LatkeBeanManagerImpl;
import org.b3log.latke.ioc.Lifecycle;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.LangPropsServiceImpl;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Mail utilities.
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 1.1.3.3, Sep 15, 2016
 * @since 1.3.0
 */
public final class Mails {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(Mails.class.getName());

    /**
     * API user.
     */
    private static final String API_USER = Symphonys.get("sendcloud.apiUser");

    /**
     * API key.
     */
    private static final String API_KEY = Symphonys.get("sendcloud.apiKey");

    /**
     * Sender email.
     */
    private static final String FROM = Symphonys.get("sendcloud.from");

    /**
     * Batch API User.
     */
    private static final String BATCH_API_USER = Symphonys.get("sendcloud.batch.apiUser");

    /**
     * Batch API key.
     */
    private static final String BATCH_API_KEY = Symphonys.get("sendcloud.batch.apiKey");

    /**
     * Batch sender email.
     */
    private static final String BATCH_FROM = Symphonys.get("sendcloud.batch.from");

    /**
     * Template configuration.
     */
    private static final Configuration TEMPLATE_CFG = new Configuration(Configuration.VERSION_2_3_23);

    /**
     * Template name - verifycode.
     */
    public static final String TEMPLATE_NAME_VERIFYCODE = "sym_verifycode";

    /**
     * Template name - weekly.
     */
    public static final String TEMPLATE_NAME_WEEKLY = "sym_weekly";

    static {
        try {
            TEMPLATE_CFG.setDirectoryForTemplateLoading(new File(Mails.class.getResource("/mail_tpl").toURI()));
            TEMPLATE_CFG.setDefaultEncoding("UTF-8");
            TEMPLATE_CFG.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
            TEMPLATE_CFG.setLogTemplateExceptions(false);
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Loads mail templates failed", e);
        }
    }

    /**
     * Refreshes (creates or updates) mail templates on <a href="http://sendcloud.sohu.com/">SendCloud</a>.
     */
    public static void refreshMailTemplates() {
        if (StringUtils.isBlank(API_USER) || StringUtils.isBlank(API_KEY)) {
            LOGGER.warn("Please configure [#### SendCloud Mail ####] section in symphony.properties");

            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                refreshVerifyCodeTemplate();
                refreshWeeklyTemplate();
            }
        }).start();
    }

    private static void refreshWeeklyTemplate() {
        final Map<String, Object> addData = new HashMap<>();
        addData.put("apiUser", API_USER);
        addData.put("apiKey", API_KEY);
        addData.put("invokeName", TEMPLATE_NAME_WEEKLY);
        addData.put("name", "每周推送");

        final LatkeBeanManager beanManager = LatkeBeanManagerImpl.getInstance();
        final LangPropsService langPropsService = beanManager.getReference(LangPropsServiceImpl.class);

        addData.put("subject", langPropsService.get("weeklyEmailSubjectLabel"));
        addData.put("templateType", "1"); // 批量邮件

        String html = "";
        addData.put("html", html);
        HttpRequest.post("http://api.sendcloud.net/apiv2/template/add").form(addData).send();

        final Map<String, Object> updateData = new HashMap<>();
        updateData.put("apiUser", API_USER);
        updateData.put("apiKey", API_KEY);
        updateData.put("invokeName", TEMPLATE_NAME_WEEKLY);

        updateData.put("html", "tttt");
        HttpRequest.post("http://api.sendcloud.net/apiv2/template/update").form(updateData).send();
    }

    private static void refreshVerifyCodeTemplate() {
        final Map<String, Object> addData = new HashMap<>();
        addData.put("apiUser", API_USER);
        addData.put("apiKey", API_KEY);
        addData.put("invokeName", TEMPLATE_NAME_VERIFYCODE);
        addData.put("name", "验证码");

        final LatkeBeanManager beanManager = LatkeBeanManagerImpl.getInstance();
        final LangPropsService langPropsService = beanManager.getReference(LangPropsServiceImpl.class);

        addData.put("subject", langPropsService.get("verifycodeEmailSubjectLabel"));
        addData.put("templateType", "0"); // 触发邮件

        String html = "";
        try {
            final Map<String, Object> dataModel = new HashMap<>();

            final Template template = TEMPLATE_CFG.getTemplate("verifycode.ftl");
            final StringWriter stringWriter = new StringWriter();
            template.process(dataModel, stringWriter);
            stringWriter.close();

            html = stringWriter.toString();
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Loads mail template failed", e);
        }

        addData.put("html", html);
        HttpRequest.post("http://api.sendcloud.net/apiv2/template/add").form(addData).send();
    }

    /**
     * Batch send mails.
     *
     * @param toMails to mails
     * @param templateName template name
     * @param subject subject
     * @param variables template variables
     */
    public static void batchSend(final String subject, final String templateName,
            final List<String> toMails, final Map<String, List<String>> variables) {
        if (null == toMails || toMails.isEmpty()) {
            return;
        }

        HttpResponse response = null;
        try {
            final Map<String, Object> formData = new HashMap<>();
            final LangPropsService langPropsService = Lifecycle.getBeanManager().getReference(LangPropsServiceImpl.class);

            formData.put("api_user", BATCH_API_USER);
            formData.put("api_key", BATCH_API_KEY);
            formData.put("from", BATCH_FROM);
            formData.put("fromname", langPropsService.get("visionLabel"));
            formData.put("subject", subject);
            formData.put("template_invoke_name", templateName);
            final JSONObject sub = new JSONObject();

            final JSONObject args = new JSONObject();
            int index = 0;
            final int size = toMails.size();
            List<String> batch = new ArrayList<>();
            while (index < size) {
                final String mail = toMails.get(index);
                batch.add(mail);
                index++;

                if (batch.size() > 99) {
                    try {
                        args.put("to", new JSONArray(batch));
                        args.put("sub", sub);
                        for (final Map.Entry<String, List<String>> var : variables.entrySet()) {
                            final JSONArray value = new JSONArray(var.getValue());
                            sub.put(var.getKey(), value);
                        }
                        formData.put("substitution_vars", args.toString());

                        response = HttpRequest.post("http://sendcloud.sohu.com/webapi/mail.send_template.json")
                                .form(formData).send();

                        LOGGER.log(Level.DEBUG, response.bodyText());
                    } catch (final Exception e) {
                        LOGGER.log(Level.ERROR, "Send mail error", e);
                    } finally {
                        if (null != response) {
                            try {
                                response.close();
                            } catch (final Exception e) {
                                LOGGER.log(Level.ERROR, "Close response failed", e);
                            }
                        }

                        batch.clear();
                    }
                }
            }

            if (!batch.isEmpty()) { // Process remains
                try {
                    args.put("to", new JSONArray(batch));
                    args.put("sub", sub);
                    for (final Map.Entry<String, List<String>> var : variables.entrySet()) {
                        final JSONArray value = new JSONArray(var.getValue());
                        sub.put(var.getKey(), value);
                    }
                    formData.put("substitution_vars", args.toString());

                    response = HttpRequest.post("http://sendcloud.sohu.com/webapi/mail.send_template.json")
                            .form(formData).send();

                    LOGGER.log(Level.DEBUG, response.bodyText());
                } catch (final Exception e) {
                    LOGGER.log(Level.ERROR, "Send mail error", e);
                } finally {
                    if (null != response) {
                        try {
                            response.close();
                        } catch (final Exception e) {
                            LOGGER.log(Level.ERROR, "Close response failed", e);
                        }
                    }
                }
            }
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Batch send mail error", e);
        }
    }

    /**
     * Sends mail.
     *
     * @param toMails to mails
     * @param templateName template name
     * @param subject subject
     * @param variables template variables
     */
    public static void send(final String subject, final String templateName,
            final List<String> toMails, final Map<String, List<String>> variables) {
        if (null == toMails || toMails.isEmpty()) {
            return;
        }

        HttpResponse response = null;
        try {
            final Map<String, Object> formData = new HashMap<>();

            final LangPropsService langPropsService = Lifecycle.getBeanManager().getReference(LangPropsServiceImpl.class);

            formData.put("api_user", API_USER);
            formData.put("api_key", API_KEY);
            formData.put("from", FROM);
            formData.put("fromname", langPropsService.get("visionLabel"));
            formData.put("subject", subject);
            formData.put("template_invoke_name", templateName);

            final JSONObject args = new JSONObject();
            args.put("to", new JSONArray(toMails));
            final JSONObject sub = new JSONObject();
            args.put("sub", sub);
            for (final Map.Entry<String, List<String>> var : variables.entrySet()) {
                final JSONArray value = new JSONArray(var.getValue());
                sub.put(var.getKey(), value);
            }
            formData.put("substitution_vars", args.toString());
            formData.put("resp_email_id", "true");

            response = HttpRequest.post("http://sendcloud.sohu.com/webapi/mail.send_template.json")
                    .form(formData).send();

            LOGGER.log(Level.DEBUG, response.bodyText());
        } catch (final Exception e) {
            LOGGER.log(Level.ERROR, "Send mail error", e);
        } finally {
            if (null != response) {
                try {
                    response.close();
                } catch (final Exception e) {
                    LOGGER.log(Level.ERROR, "Close response failed", e);
                }
            }
        }
    }

    /**
     * Private constructor.
     */
    private Mails() {
    }
}
