package com.heroku.devcenter;


import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.QueueingConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WorkerMain {

    final static Logger logger = LoggerFactory.getLogger(WorkerMain.class);

    public static void main(String[] args) throws Exception {

        ConnectionFactory factory = RabbitFactoryUtil.getConnectionFactory();
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();
        String exchangeName = "sample-exchange";
        String queueName = "sample-queue";
        String routingKey = "sample-key";
        channel.exchangeDeclare(exchangeName, "direct", true);
        channel.queueDeclare(queueName, true, false, false, null);
        channel.queueBind(queueName, exchangeName, routingKey);
        QueueingConsumer consumer = new QueueingConsumer(channel);
        channel.basicConsume(queueName, true, consumer);

        while (true) {
            QueueingConsumer.Delivery delivery = consumer.nextDelivery();
            if (delivery != null) {
                String msg = new String(delivery.getBody(), "UTF-8");
                logger.info("Message Received: " + msg);
            }
        }

    }

}
