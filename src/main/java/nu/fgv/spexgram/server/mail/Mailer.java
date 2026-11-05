/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package nu.fgv.spexgram.server.mail;

import lombok.extern.slf4j.Slf4j;
import nu.fgv.spexgram.server.config.SpexgramProperties;
import nu.fgv.spexgram.server.util.Locales;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Sends plain text notifications through the configured mail server, as the other FGV servers do, in the recipient's
 * language. Without a mail server nothing is sent; a refused message is logged without the address and never fails
 * what caused it.
 *
 * @author Anders Jacobsson
 * @since 1.0
 */
@Slf4j
@Component
public class Mailer {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MessageSource messageSource;
    private final String from;
    private final String webUrl;

    public Mailer(final ObjectProvider<JavaMailSender> mailSender, final MessageSource messageSource,
                  final SpexgramProperties properties) {
        this.mailSender = mailSender;
        this.messageSource = messageSource;
        this.from = properties.mail().from();
        this.webUrl = properties.webUrl().replaceAll("/+$", "");
    }

    /**
     * A message from {@code messages_{sv,en}.properties}.
     */
    public String text(final String key, final @Nullable Locale locale, final Object... arguments) {
        return messageSource.getMessage(key, arguments, key, Locales.supported(locale));
    }

    /**
     * The web's address of a page, e.g. {@code /albums/<id>}.
     */
    public String link(final String path) {
        return webUrl + path;
    }

    /**
     * @return whether the mail server took the message
     */
    public boolean send(final String to, final String subject, final List<String> lines) {
        final JavaMailSender sender = mailSender.getIfAvailable();

        if (sender == null) {
            log.info("No mail server is configured, so a notification was not sent: {}", subject);
            return false;
        }

        final SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject.replaceAll("[\\r\\n]+", " "));
        message.setText(String.join("\n", lines));
        try {
            sender.send(message);
            return true;
        } catch (final MailException e) {
            log.warn("The mail server refused a notification: {}", e.getClass().getSimpleName());
            return false;
        }
    }
}
