Scheduled Jobs with Custom Clock Processes in Java with Quartz
==============================================================

There are numerous ways to [schedule background jobs](https://devcenter.heroku.com/articles/scheduled-jobs-custom-clock-processes) in Java applications.  This article will teach you how to setup a Java application that uses the Quartz scheduling library along with RabbitMQ to create a scalable and reliable method of doing background processing in Java applications on Heroku.

Many of the common methods for background processing in Java advocate running background jobs within the same application as the web tier.  This approach has scalability and reliability constraints.  A better approach is to move background jobs into separate processes so that the web tier is distinctly separate from the background processing tier.  This allows the web tier to be exclusively for handling web requests.  The scheduling of jobs should also be it's own tier that puts jobs onto a queue.  The worker processing tier can then be scaled independently from the rest of the application.

<div class="note" markdown="1">
The source for this article's reference application is [available on GitHub](http://github.com/heroku/devcenter-java-quartz-rabbitmq).
</div>

## Prerequisites

You will need to have the [Heroku Toolbelt](https://toolbelt.heroku.com) installed as well as [Maven 3.0.4](http://maven.apache.org/download.html) to follow along.

To clone the sample project to your local computer run:

    :::term
    $ git clone https://github.com/heroku/devcenter-java-quartz-rabbitmq.git
    Cloning into devcenter-java-quartz-rabbitmq...
    
    $ cd devcenter-java-quartz-rabbitmq/

Scheduling jobs with Quartz
---------------------------

A job scheduling [custom clock process](https://devcenter.heroku.com/articles/scheduled-jobs-custom-clock-processes) will be used to create jobs and add them to a queue.  To setup a custom clock process use the [Quartz](http://quartz-scheduler.org) library.  In Maven the dependency is declared with:

<div class="callout" markdown="1">
See the reference app's [pom.xml](https://github.com/heroku/devcenter-java-quartz-rabbitmq/blob/master/pom.xml) for the full Maven build definition.
</div>
    
    :::xml
    <dependency>
        <groupId>org.quartz-scheduler</groupId>
        <artifactId>quartz</artifactId>
        <version>2.1.5</version>
    </dependency>

Now a Java application can be used to schedule jobs.  Here is an example:

    :::java
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

This simple example creates a `HelloJob` every two seconds that simply logs a message.  Quartz has a [very extensive API](http://quartz-scheduler.org/api/2.1.5/org/quartz/SimpleScheduleBuilder.html) for creating `Trigger` schedules.

To test this application locally you can run the Maven build and then run the `SchedulerMain` Java class:

    :::term
    $ mvn package
    INFO] Scanning for projects...
    [INFO]                                                                         
    [INFO] ------------------------------------------------------------------------
    [INFO] Building devcenter-java-quartz-rabbitmq 1.0-SNAPSHOT
    [INFO] ------------------------------------------------------------------------
    
    $ java -cp target/classes:target/dependency/* com.heroku.devcenter.SchedulerMain
    ...
    66 [main] INFO org.quartz.impl.StdSchedulerFactory - Quartz scheduler 'DefaultQuartzScheduler' initialized from default resource file in Quartz package: 'quartz.properties'
    66 [main] INFO org.quartz.impl.StdSchedulerFactory - Quartz scheduler version: 2.1.5
    66 [main] INFO org.quartz.core.QuartzScheduler - Scheduler DefaultQuartzScheduler_$_NON_CLUSTERED started.
    104 [DefaultQuartzScheduler_Worker-1] INFO com.heroku.devcenter.SchedulerMain - HelloJob executed
    2084 [DefaultQuartzScheduler_Worker-2] INFO com.heroku.devcenter.SchedulerMain - HelloJob executed

Press `Ctrl-C` to exit the app.

If the `HelloJob` actually did work itself then we would have a runtime bottleneck because we could not scale the scheduler while avoiding duplicate jobs being scheduled.  Quartz does have a JDBC module that can use a database to prevent jobs from being duplicated but a simpler approach is to only run one instance of the scheduler and have the scheduled jobs added to a message queue where they can be processes in parallel by job worker processes.

Queuing jobs with RabbitMQ
----------------------------

RabbitMQ can be used as a message queue so the scheduler process can be used just to add jobs to a queue and worker processes can be used to grab jobs from the queue and process them.  To add the RabbitMQ client library as a dependency in Maven specify the following in dependencies block of the `pom.xml` file:

    :::xml
    <dependency>
        <groupId>com.rabbitmq</groupId>
        <artifactId>amqp-client</artifactId>
        <version>2.8.2</version>
    </dependency>

If you want to test this locally then [install RabbitMQ](http://www.rabbitmq.com/download.html) and set an environment variable that will provide the application the connection information to your RabbitMQ server.

On Windows:

        $ set CLOUDAMQP_URL="amqp://guest:guest@localhost:5672/%2f"

On Mac/Linux:

        $ export CLOUDAMQP_URL="amqp://guest:guest@localhost:5672/%2f"


The `CLOUDAMQP_URL` environment variable will be used by the scheduler and worker processes to connect to the shared message queue.  This example uses that environment variable because that is the way the [CloudAMQP Heroku Add-on](https://addons.heroku.com/cloudamqp) will provide it's connection information to the application.

The `SchedulerMain` class needs to be updated to add a new message onto a queue every time the `HelloJob` is executed.  Here are the new `SchedulerMain` and `HelloJob` classes from the [SchedulerMain.java file in the sample project](https://github.com/heroku/devcenter-java-quartz-rabbitmq/blob/master/src/main/java/com/heroku/devcenter/SchedulerMain.java):

    :::java
    package com.heroku.devcenter;
    
    
    import com.rabbitmq.client.Channel;
    import com.rabbitmq.client.Connection;
    import com.rabbitmq.client.ConnectionFactory;
    import com.rabbitmq.client.MessageProperties;
    import org.quartz.*;
    import org.quartz.impl.StdSchedulerFactory;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;
    
    import java.util.HashMap;
    import java.util.Map;
    
    import static org.quartz.JobBuilder.newJob;
    import static org.quartz.SimpleScheduleBuilder.repeatSecondlyForever;
    import static org.quartz.TriggerBuilder.newTrigger;
    
    public class SchedulerMain {
    
        final static Logger logger = LoggerFactory.getLogger(SchedulerMain.class);
        final static ConnectionFactory factory = new ConnectionFactory();
        
        public static void main(String[] args) throws Exception {
            factory.setUri(System.getenv("CLOUDAMQP_URL"));
            Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
    
            scheduler.start();
    
            JobDetail jobDetail = newJob(HelloJob.class).build();
            
            Trigger trigger = newTrigger()
                    .startNow()
                    .withSchedule(repeatSecondlyForever(5))
                    .build();
    
            scheduler.scheduleJob(jobDetail, trigger);
        }
    
        public static class HelloJob implements Job {
            
            @Override
            public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
                
                try {
                    Connection connection = factory.newConnection();
                    Channel channel = connection.createChannel();
                    String queueName = "work-queue-1";
                    Map<String, Object> params = new HashMap<String, Object>();
                    params.put("x-ha-policy", "all");
                    channel.queueDeclare(queueName, true, false, false, params);
    
                    String msg = "Sent at:" + System.currentTimeMillis();
                    byte[] body = msg.getBytes("UTF-8");
                    channel.basicPublish("", queueName, MessageProperties.PERSISTENT_TEXT_PLAIN, body);
                    logger.info("Message Sent: " + msg);
                    connection.close();
                }
                catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
    
            }
            
        }
    
    }


In this example every time the `HelloJob` is executed it adds a message onto a RabbitMQ message queue simply containing a String with the time the String was created.  Running the updated `SchedulerMain` should add a new message to the queue every 5 seconds.

Processing jobs
---------------

Next, create a Java application that will pull messages from the queue and handle them.  This application will also use the `RabbitFactoryUtil` to get a connection to RabbitMQ from the `CLOUDAMQP_URL` environment variable.  Here is the `WorkerMain` class from the [WorkerMain.java file in the example project](https://github.com/heroku/devcenter-java-quartz-rabbitmq/blob/master/src/main/java/com/heroku/devcenter/WorkerMain.java):

    :::java
    package com.heroku.devcenter;
    
    
    import com.rabbitmq.client.Channel;
    import com.rabbitmq.client.Connection;
    import com.rabbitmq.client.ConnectionFactory;
    import com.rabbitmq.client.QueueingConsumer;
    import org.slf4j.Logger;
    import org.slf4j.LoggerFactory;
    
    import java.util.Collections;
    import java.util.HashMap;
    import java.util.Map;
    
    public class WorkerMain {
    
        final static Logger logger = LoggerFactory.getLogger(WorkerMain.class);
    
        public static void main(String[] args) throws Exception {
    
            ConnectionFactory factory = new ConnectionFactory();
            factory.setUri(System.getenv("CLOUDAMQP_URL"));
            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();
            String queueName = "work-queue-1";
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("x-ha-policy", "all");
            channel.queueDeclare(queueName, true, false, false, params);
            QueueingConsumer consumer = new QueueingConsumer(channel);
            channel.basicConsume(queueName, false, consumer);
           
            while (true) {
                QueueingConsumer.Delivery delivery = consumer.nextDelivery(); 
                if (delivery != null) {
                    String msg = new String(delivery.getBody(), "UTF-8");
                    logger.info("Message Received: " + msg);
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                }
            }
    
        }
    
    }


This class simply waits for new messages on the message queue and logs that it received them.  You can run this example locally by doing a build and then running the `WorkerMain` class:

    $ mvn package
    $ java -cp target/classes:target/dependency/* com.heroku.devcenter.WorkerMain

You can also run multiple instances of this example locally to see how the job processing can be horizontally distributed.

Running on Heroku
-----------------

Now that you have everything working locally you can run this on Heroku.  First declare the [process model](https://devcenter.heroku.com/articles/process-model) in a new file named `Procfile` containing:

    scheduler: java $JAVA_OPTS -cp target/classes:target/dependency/* com.heroku.devcenter.SchedulerMain
    worker: java $JAVA_OPTS -cp target/classes:target/dependency/* com.heroku.devcenter.WorkerMain

This defines two process types that can be executed on Heroku; one named `scheduler` for the `SchedulerMain` app and one named `worker` for the `WorkerMain` app.

To run on Heroku you will need to push a Git repository to Heroku containing the Maven build descriptor, source code, and `Procfile`.  If you cloned the example project then you already have a Git repository.  If you need to create a new git repository containing these files, run:

    :::term
    $ git init
    $ git add src pom.xml Procfile
    $ git commit -m init

Create a new application on Heroku from within the project's root directory:

    :::term
    $ heroku create
    Creating furious-cloud-2945... done, stack is cedar
    http://furious-cloud-2945.herokuapp.com/ | git@heroku.com:furious-cloud-2945.git
    Git remote heroku added

Then add the CloudAMQP add-on to your application:

    :::term
    $ heroku addons:add cloudamqp
    Adding cloudamqp to furious-cloud-2945... done, v2 (free)
    cloudamqp documentation available at: https://devcenter.heroku.com/articles/cloudamqp

Now push your Git repository to Heroku:

    :::term
    $ git push heroku master
    Counting objects: 165, done.
    Delta compression using up to 2 threads.
    ...
    -----> Heroku receiving push
    -----> Java app detected
    ...
    -----> Discovering process types
           Procfile declares types -> scheduler, worker
    -----> Compiled slug size is 1.4MB
    -----> Launching... done, v5
           http://furious-cloud-2945.herokuapp.com deployed to Heroku

This will run the Maven build for your project on Heroku and create a slug file containing the executable assets for your application.  To run the application you will need to allocate dynos to run each process type.  You should only allocate one dyno to run the `scheduler` process type to avoid duplicate job scheduling.  You can allocate as many dynos as needed to the `worker` process type since it is event driven and parallelizable through the RabbitMQ message queue.

To allocate one dyno to the `scheduler` process type run:

    :::term
    $ heroku ps:scale scheduler=1
    Scaling scheduler processes... done, now running 1

This should begin adding messages to the queue every file seconds. To allocate two dynos to the `worker` process type run:

    :::term
    $ heroku ps:scale worker=2
    Scaling worker processes... done, now running 2

This will provision two dynos, each which will run the `WorkerMain` app and pull messages from the queue for processing. You can verify that this is happening by watching the Heroku logs for your application.  To open a feed of your logs run:

    :::term
    $ heroku logs -t
    2012-06-26T22:26:47+00:00 app[scheduler.1]: 100223 [DefaultQuartzScheduler_Worker-1] INFO com.heroku.devcenter.SchedulerMain - Message Sent: Sent at:1340749607126
    2012-06-26T22:26:47+00:00 app[worker.2]: 104798 [main] INFO com.heroku.devcenter.WorkerMain - Message Received: Sent at:1340749607126
    2012-06-26T22:26:52+00:00 app[scheduler.1]: 105252 [DefaultQuartzScheduler_Worker-2] INFO com.heroku.devcenter.SchedulerMain - Message Sent: Sent at:1340749612155
    2012-06-26T22:26:52+00:00 app[worker.1]: 109738 [main] INFO com.heroku.devcenter.WorkerMain - Message Received: Sent at:1340749612155

In this example execution the scheduler creates 2 messages which are handled by the two different worker dynos (`worker.1` and `worker.2`).  This shows that the work is being scheduled and distributed correctly.

Further learning
---------------

This example application just shows the basics for architecting a scalable and reliable system for scheduling and processing background jobs.  To learn more see:

* [RabbitMQ](http://www.rabbitmq.com/)
* [Quartz](http://quartz-scheduler.org/)
