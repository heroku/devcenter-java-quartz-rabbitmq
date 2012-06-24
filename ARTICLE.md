Scheduled Jobs with Custom Clock Processes in Java with Quartz
==============================================================

There are numerous ways to schedule background jobs in Java applications.  This article will teach you how to setup a Java application that uses the Quartz scheduling library along with RabbitMQ to create a scalable and reliable method of doing background processing in Java applications on Heroku.

Many of the common methods for background processing in Java advocate running background jobs within the same application as the web tier.  This approach has scalability and reliability constraints.  A better approach is to move background jobs into seperate processes so that the web tier is distinictly seperate from the background processing tier.  This allows the web tier to be exclusively for handling web requests.  The scheduling of jobs should also be it's own tier that puts jobs onto a queue.  The worker processing tier can then be scaled independently from the rest of the application.

For more information on this architecture see the [Scheduled Jobs and Custom Clock Processes](https://devcenter.heroku.com/articles/scheduled-jobs-custom-clock-processes) article.  All of the source code for this article is [available on GitHub](http://github.com/heroku/devcenter-java-quartz-rabbitmq).  To follow along you will need the following prerequistes:

* [Heroku Toolbelt](http://toolbelt.heroku.com)
* [Maven 3.0.4](http://maven.org/download) *** VERIFY URL ***
* [Git Command Line](http://git-scm.org/download) *** VERIFY URL ***

To clone the sample project to your local computer run:

    $ git clone *** NEED URL ***


Scheduling Jobs with Quartz
---------------------------

A Job Scheduler / Custom Clock Process will be used to create jobs and add them to a queue.  To setup a custom clock process use the [Quartz](http://www.quartzscheduler.com) ***VERIFY URL*** library.  With Maven the dependency will be:
    
    <dependency>
        <groupId>org.quartz-scheduler</groupId>
        <artifactId>quartz</artifactId>
        <version>2.1.5</version>
    </dependency>

See the project's [pom.xml](http://github.com/heroku/devcenter-java-quartz-rabbitmq/master/blob/pom.xml) ***VERIFY URL*** for the full Maven build definition.

Now a Java application can be used to schedule jobs.  Here is an example:

    package com.heroku.devcenter;
    
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
                logger.info("HelloJob executed");
            }
            
        }
    
    }

Using Quartz a new `Scheduler` is created and started.  Then a new `JobDetail` is created for the `HelloJob` job.  In this example the `HelloJob` simply logs a simple message.  In Quartz a `JobDetail` object is the definition of a job.  The job itself is created using a `Trigger`.  The trigger in this application runs every 2 seconds, continuning forever.  The `scheduler` is then told to schedule the `jobDetail` job to run based on the `trigger`.  Quartz has a [very extensive API]() ***NEED URL*** for creating `Trigger` schedules.

To test this application locally you can run the Maven build and then run the `SchedulerMain` Java class:

    $ mvn package
    $ java -cp target/classes:target/dependency/* com.heroku.devcenter.SchedulerMain

If the `HelloJob` actually did work itself then we would have a runtime bottleneck because we could not scale the scheduler and avoid duplicate jobs being scheduled.  Quartz does have a JDBC module that can use a database to prevent jobs from being duplicated but a simplier approach is to only run one instance of the scheduler and have the scheduled jobs added to a message queue where they can be processes in parrallel by job worker processes.


Queing Jobs with a RabbitMQ
---------------------------

RabbitMQ can be used as a message queue so the scheduler process can be used just to add jobs to a queue and worker processes can be used to grab jobs from the queue and process them.  To add the RabbitMQ client library as a dependency in Maven specify the following in dependencies block of the `pom.xml` file:

    <dependency>
        <groupId>com.rabbitmq</groupId>
        <artifactId>amqp-client</artifactId>
        <version>2.8.2</version>
    </dependency>

If you want to test this locally then [install RabbitMQ]() ***NEED URL*** and set an environment variable that will provide the application the connection information to your RabbitMQ server.

* On Windows:

        $ set RABBITMQ_URL="amqp://guest:guest@localhost:5672/%2f"

* On Mac/Linux:

        $ export RABBITMQ_URL="amqp://guest:guest@localhost:5672/%2f"

Now use a helper class to connect to RabbitMQ based on the `RABBITMQ_URL` environment variable (the `RabbitFactoryUtil.java` file [in the example project]() ***NEED URL***):

    package com.heroku.devcenter;
    
    import static java.lang.System.getenv;
    import java.net.URI;
    import java.net.URISyntaxException;
    
    import com.rabbitmq.client.ConnectionFactory;
    
    public class RabbitFactoryUtil {
    
        public static ConnectionFactory getConnectionFactory() throws URISyntaxException {
            ConnectionFactory factory = new ConnectionFactory();
    
            URI uri = new URI(getenv("RABBITMQ_URL"));
            factory.setUsername(uri.getUserInfo().split(":")[0]);
            factory.setPassword(uri.getUserInfo().split(":")[1]);
            factory.setHost(uri.getHost());
            factory.setPort(uri.getPort());
            factory.setVirtualHost(uri.getPath().substring(1));
    
            return factory;
        }
    
    }

The `RabbitFactoryUtil` class reads the `RABBITMQ_URL` environment variable and uses the connection information in the URL to create and return a RabbitMQ `ConnectionFactory` instance.  This will be used by the scheduler and worker proceses to connect to the shared message queue.  This example uses an environment variable named `RABBITMQ_URL` because that is the way the [CloudAMQP Heroku Add-on]() ***NEED URL*** will provide it's connection information to the application.

The `SchedulerMain` class needs to be updated to add a new message onto a queue every time the `HelloJob` is executed.  Here is the new `HelloJob` class from the [SchedulerMain.java file in the sample project](): ***NEED URL***

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

In this example every time the `HelloJob` is executed it adds a message onto a RabbitMQ message queue simply containing a String with the time the String was created.  Running the updated `SchedulerMain` should add a new message to the queue every 2 seconds.


Processing Jobs From a Queue
----------------------------

Now lets create a Java application that will pull messages from the queue and handle them.  This application will also use the `RabbitFactoryUtil` to get a connection to RabbitMQ from the `RABBITMQ_URL` environment variable.  Here is the `WorkerMain` class from the [WorkerMain.java file in the example project]() *** NEED URL ***:

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

This class simply waits for new messages on the message queue and logs that it received them.  You can run this example locally by doing a build and then running the `WorkerMain` class::

   $ mvn package
   $ java -cp target/classes:target/dependency/* com.heroku.devcenter.WorkerMain

You can also run multiple instances of this example locally to see how the job processing can be horizontally distributed.


Running on Heroku
-----------------

Now that you have everything working locally you can run this on Heroku.  First create a new file named `Procfile` containing:

    scheduler: java $JAVA_OPTS -cp target/classes:target/dependency/* com.heroku.devcenter.SchedulerMain
    worker: java $JAVA_OPTS -cp target/classes:target/dependency/* com.heroku.devcenter.WorkerMain

This defines two processes that can be executed on Heroku; one named `scheduler` for the `SchedulerMain` application and one named `worker` for the `WorkerMain` application.

To run these applications on Heroku you will need to push a Git repository to Heroku containing the Maven build descriptor, source code, and Procfile.  If you cloned the example project then you already have a git repository.  If you need to create a new git repository containing these files, run:

    $ git init
    $ git add src pom.xml Procfile
    $ git commit -m init

With the necessary files in a Git repository create a new application on Heroku from within the project's root directory:

    $ heroku create

Then add the CloudAMQP add-on to your application:

    $ heroku addons-add cloudampq

Now push your Git repository to Heroku:

    $ git push heroku master

This will run the Maven build for your project on Heroku and create a slug file containing the executable assets for your application.  To run the application you will need to allocate Dynos to run each process.  You should only allocate one Dyno to run the `scheduler` process to avoid duplicate job scheduling.  You can allocate as many Dynos as needed to the `worker` process since it is event driven and parallelizable through the RabbitMQ message queue.

To allocate one Dyno to the `scheduler` process run:

    $ heroku scale scheduler=1

This should begin adding messages to the queue every two seconds.

To allocate two Dynos to the `worker` process run:

    $ heroko scale worker=2

This will allocate two Dynos that will run the `WorkerMain` appliction which will pull messages from the queue and process them.

You can verify that this is happening by watching the Heroku logs for your application.  To open a feed of your logs run:

    $ heroku logs -t

You should see something like:

*** NEED OUTPUT ***


Further Leaning
---------------

This example application just shows the basics for architecting a scalable and reliable system for scheduling and processing beackground jobs.  To learn more see:

* RabbitMQ
* Quartz