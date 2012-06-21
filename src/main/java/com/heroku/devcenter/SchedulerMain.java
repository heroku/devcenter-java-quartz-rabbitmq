package com.heroku.devcenter;


import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.quartz.JobBuilder.newJob;
import static org.quartz.SimpleScheduleBuilder.repeatSecondlyForever;
import static org.quartz.TriggerBuilder.newTrigger;

public class SchedulerMain {

    final static Logger logger = LoggerFactory.getLogger(SchedulerMain.class);
    
    public static void main(String[] args) throws Exception {
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();

        scheduler.start();

        JobDetail jobDetail = newJob(HelloJob.class).build();
        
        Trigger trigger = newTrigger()
                .startNow()
                .withSchedule(repeatSecondlyForever(2))
                .build();

        scheduler.scheduleJob(jobDetail, trigger);
    }

    public static class HelloJob implements Job {
        
        @Override
        public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
            
            try {
                ConnectionFactory factory = RabbitFactoryUtil.getConnectionFactory();
                Connection connection = factory.newConnection();
                Channel channel = connection.createChannel();
                String exchangeName = "sample-exchange";
                String queueName = "sample-queue";
                String routingKey = "sample-key";
                channel.exchangeDeclare(exchangeName, "direct", true);
                channel.queueDeclare(queueName, true, false, false, null);
                channel.queueBind(queueName, exchangeName, routingKey);

                String msg = "Sent at:" + System.currentTimeMillis();
                byte[] body = msg.getBytes("UTF-8");
                channel.basicPublish(exchangeName, routingKey, null, body);
                logger.info("Message Sent: " + msg);
            }
            catch (Exception e) {
                logger.error(e.getMessage());
            }

        }
        
    }

}
