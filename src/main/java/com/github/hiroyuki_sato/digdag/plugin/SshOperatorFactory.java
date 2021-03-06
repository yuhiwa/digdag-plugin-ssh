package com.github.hiroyuki_sato.digdag.plugin;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import io.digdag.client.config.Config;
import io.digdag.client.config.ConfigException;
import io.digdag.spi.Operator;
import io.digdag.spi.OperatorContext;
import io.digdag.spi.OperatorFactory;
import io.digdag.spi.SecretProvider;
import io.digdag.spi.TaskResult;
import io.digdag.spi.TemplateEngine;
import io.digdag.util.BaseOperator;
import io.digdag.util.RetryExecutor;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.BaseFileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static io.digdag.util.RetryExecutor.retryExecutor;

public class SshOperatorFactory
        implements OperatorFactory
{
    @SuppressWarnings("unused")
    private final TemplateEngine templateEngine;

    private static Logger logger = LoggerFactory.getLogger(SshOperatorFactory.class);

    public SshOperatorFactory(TemplateEngine templateEngine)
    {
        this.templateEngine = templateEngine;
    }

    public String getType()
    {
        return "ssh";
    }


    @Override
    public Operator newOperator(OperatorContext context)
    {
        return new SshOperator(context);
    }

    private class SshOperator
            extends BaseOperator
    {
        public SshOperator(OperatorContext context)
        {
            super(context);
        }

        private final static int defaultCommandTimeout = 60;
        private final static int defaultInitialRetryWait = 500;
        private final static int defaultMaxRetryWait = 2000;
        private final static int defaultMaxRetryLimit = 3;

        @Override
        public TaskResult runTask()
        {
            Config params = request.getConfig().mergeDefault(
                    request.getConfig().getNestedOrGetEmpty("ssh"));

            String command = params.get("_command", String.class);
            String host = params.get("host", String.class);
            int port = params.get("port", int.class, 22);
            int cmd_timeo = params.get("command_timeout", int.class, defaultCommandTimeout);
            int initial_retry_wait = params.get("initial_retry_wait", int.class, defaultInitialRetryWait);
            int max_retry_wait = params.get("max_retry_wait", int.class, defaultMaxRetryWait);
            int max_retry_limit = params.get("max_retry_limit", int.class, defaultMaxRetryLimit);

            SSHClient ssh = null;

            try {
                try {
                    logger.info(String.format("Connecting %s:%d", host, port));

                    RetryExecutor retryExecutor = retryExecutor()
                            .retryIf(exception -> true)
                            .withInitialRetryWait(initial_retry_wait)
                            .withMaxRetryWait(max_retry_wait)
                            .onRetry((exception, retryCount, retryLimit, retryWait) -> logger.warn("Connection failed: retry {} of {} (wait {}ms)", retryCount, retryLimit, retryWait, exception))
                            .withRetryLimit(max_retry_limit);

                    try {
                        ssh = retryExecutor.run(() -> {
                            try {
                                SSHClient sshTmp = new SSHClient();
                                setupHostKeyVerifier(sshTmp);
                                sshTmp.connect(host, port);
                                return sshTmp;
                            }
                            catch (Exception e) {
                                throw Throwables.propagate(e);
                            }
                        });
                    }
                    catch (RetryExecutor.RetryGiveupException ex) {
                        throw Throwables.propagate(ex.getCause());
                    }

                    try {

                        authorize(ssh);
                        final Session session = ssh.startSession();

                        logger.info(String.format("Execute command: %s", command));
                        final Session.Command result = session.exec(command);
                        result.join(cmd_timeo, TimeUnit.SECONDS);

                        int status = result.getExitStatus();

                        boolean stdout_log = params.get("stdout_log",boolean.class,true);
                        if( stdout_log ) {
                            logger.info("STDOUT output");
                            outputResultLog(IOUtils.readFully(result.getInputStream()).toString());
                        }


                        boolean stderr_log = params.get("stderr_log",boolean.class,false);
                        if( stderr_log ){
                            logger.info("STDERR output");
                            outputResultLog(IOUtils.readFully(result.getErrorStream()).toString());
                        }
                        logger.info("Status: " + status);
                        if (status != 0) {
                            throw new RuntimeException(String.format("Command failed with code %d", status));
                        }
                    }
                    catch (ConnectionException ex) {
                        throw Throwables.propagate(ex);
                    }
                    finally {
                        ssh.close();
                    }
                }
                finally {
                    ssh.disconnect();
                }
            } catch ( IOException ex){
                throw Throwables.propagate(ex);
            }

            return TaskResult.empty(request);
        }

        private void authorize(SSHClient ssh)
        {
            Config params = request.getConfig().mergeDefault(
                    request.getConfig().getNestedOrGetEmpty("ssh"));

            String user = params.get("user", String.class);
            SecretProvider secrets = context.getSecrets().getSecrets("ssh");

            try {
                if (params.get("password_auth", Boolean.class, false)) {
                    Optional<String> password = getPassword(secrets, params);
                    if (!password.isPresent()) {
                        throw new RuntimeException("password not set");
                    }
                    logger.info(String.format("Authenticate user %s with password", user));
                    ssh.authPassword(user, password.get());
                }
                else {
                    Optional<String> publicKey = secrets.getSecretOptional("public_key");
                    Optional<String> privateKey = secrets.getSecretOptional("private_key");
                    Optional<String> publicKeyPass = secrets.getSecretOptional("public_key_passphrase");
                    if (!publicKey.isPresent()) {
                        throw new RuntimeException("public_key not set");
                    }
                    if (publicKeyPass.isPresent()) {
                        // TODO
                        // ssh.authPublickey(user,publicKey.get());
                        throw new ConfigException("public_key_passphrase doesn't support yet");
                    }
                    if (!privateKey.isPresent()) {
                        throw new ConfigException("private key not set");
                    }

                    OpenSSHKeyFile keyfile = new OpenSSHKeyFile();

                    keyfile.init(privateKey.get(), publicKey.get());
                    logger.info(String.format("Authenticate user %s with public key", user));
                    ssh.authPublickey(user, keyfile);
                }
            }
            catch (UserAuthException | TransportException ex) {
                throw Throwables.propagate(ex);
            }
        }

        private Optional<String> getPassword(SecretProvider secrets, Config params)
        {
            Optional<String> passwordOverrideKey = params.getOptional("password_override", String.class);
            if (passwordOverrideKey.isPresent()) {
                return Optional.of(secrets.getSecret(passwordOverrideKey.get()));
            }
            else {
                return secrets.getSecretOptional("password");
            }
        }

        private void setupHostKeyVerifier(SSHClient ssh)
        {
/*
            try {
*/
                ssh.addHostKeyVerifier(new PromiscuousVerifier());
/*
                ssh.loadKnownHosts();
            }
            catch (IOException ex) {
                throw Throwables.propagate(ex);
            }
*/
        }

        private void outputResultLog(String log)
        {
            for(String msg: log.split("\r?\n")){
                logger.info("  " + msg);
            }
        }
    }
}
