# Index
1. [Design Focused Implementation Blog](https://github.com/Biswas57/Tributary-Cluster/blob/main/Blogging%26Design/tributary_cluster_blog.md#tributary-cluster-design-focused-blog)
3. [Reflection Blog](https://github.com/Biswas57/kaflow/blob/main/Blogging%26Design/tributary_cluster_blog.md#ongoing-updates-and-implementations)

# Tributary Cluster Design-Focused Blog
This structure might change because in the real Kafka Producer and Consumers exist outside the one cluster and there are additional components called brokers that allow me to add more low-level features to the system. However, for the purposes of this project, I will keep the system simple and easy to understand.
1. Content Structure:
       Tributary Cluster &rarr; Topics &rarr; Partitions &rarr; Messages.
2. Consumer Structure:
       Tributary Cluster &rarr; Consumer Groups &rarr; Consumers.
4. Producer Structure:
       Tributary Cluster &rarr; Producers.

<img src="images/tributaryClusterExample.png" width="230px" /> <img src="images/topicExample.png" width="550px" />

- **Tributary Cluster:** The top-level singleton component that covers the entire system.
    - **Topics:** Categories or channels within the Tributary Cluster where messages are allocated based on their content or purpose.
        - **Partitions:** Subsets within each topic where messages are stored.
            - Partitions help in distributing the data load and allow for parallel processing.
            - **Producers:** Entities that publish messages to specific partitions within a singular.
                - **Messages:** Include a timestamp, an identifier, payload type, a key, and the actual data (value).
            - **Consumer Groups:** these are groups of consumers that work together to process messages from all partitions of a topic.
                - **Consumers:** Individuals within consumer groups who actually process the messages.

**Producers**
- Generate and send messages to the system.
- Event Generation: Producers are made to create and send messages to the system. 
    - These messages can be related to a wide variety of actions, such as user commands or system alerts.
    - Ther also allocate whether the partition the message is set is random or a specific key is give
- Message Attributes: Each message includes a timestamp, an identifier, payload type, a key which is optional, and the actual data called the value.

Types of Producers:
- Random Producers: They send messages without specifying a partition (random key), allowing the system to distribute these messages randomly.
- Manual Producers: They specify which partition to send a message to, using a key that directs the system on how to route the message.

Other Notes:
- The system must support the integration of new types of producers without major changes to the existing infrastructure.

**Consumers**
- Read and process messages from assigned partitions.
- Message Reading: Consumers read and process messages from their assigned partitions. 
    - They make sure messages are processed in the order they are received to maintain data integrity.
        - In a priority queue style format with a message replay and offset functionality
- Partition Handling: Each consumer handles one partition at a time but can be assigned to multiple partitions to balance load.

**Consumer Groups**
- Message consumption: Consumer groups consist of multiple consumers working together to process messages from all partitions within a topic.
- Load Balancing: The system distributes partitions among consumers in a group to decrease processing time
    - There are 2 distinct distribution methods
 
<p align="center">
  <img src="images/consumerAllocation1.png" width="500px" />
</p>

**Rebalancing (Message Distribution) Strategies:**
- Range Rebalancing: Partitions are evenly divided based on the number of consumers, with adjustments made for odd counts.
- Round Robin Rebalancing: Partitions are allocated in a rotating manner to ensure even distribution of workload among consumers.

**Message Replay**
- Messages from a certain offset onwards are processed, until the most recent message at the latest offset is reached to catch errors and failures or for processing historical data. This feature is crucial when a system feature may not be working as it's supposed to.
- Backtrack Replay: Consumers can replay messages from a specified backtrack in their partition, ie. -2 = 2nd latest message processed
- Controlled Replay: Consumers can replay messages from a specific offset. ie. 2 = 2nd message in the partition


![](images/controlledReplay.png)
> ℹ  NOTE: The above image demonstrates a consumer starting at offset 6 that performed normal consumption until offset 9. This consumer then triggered a
> controlled replay from offset 4 that played back all the messages from that offset until the most recently consumed message (i.e messages 6, 7, 8 and 9
> were consumed again).

### Design Considerations

Concurrency:
- Thread Safety: To handle concurrent operations without issues, the system will use synchronization via the Singleton Pattern for the Tributary Cluster.
- Java synchronization and Executor Service Library to manage parallel message handling ensuring thread-safe operations across producer and consumer threads for real-time data integrity in the event processing pipeline.

Generics:
- Type Flexibility: The system uses Java generics to handle messages of any type, making the system able to adapt to different data types without duplicating code.

### Initial UML Diagram
My [initial UML design](UML_1st_draft.pdf) was extremeley simple and had very little thought as to how the system would operate. Chose this approach because I would be overwhelmed if I had done otherwise

### Java API Design
**api:** This is the outward known side of our system, where the interfaces and classes essential for users to interact with the Tributary system are located.
- TributaryCluster: Manages the collection of topics.
- Topic: Handles operations on a specific topic.
- Partition: Manages message storage and retrieval within a topic.
- Producer ( Abstract Class and its subclasses RandomProducer and ManualProducer): Creates and sends messages to the system.
- ConsumerGroup: Manages a group of consumers for processing messages with an assigned topic.
- Consumer: Processes messages from assigned partitions.

**core:** Contains the internal logic that contains the workings of our API. 
- TributaryController: Manages the handling of CLI commands
- ObjectFactory: Creates objects like topics, consumer and groups, producers, events and partitions.
- Other Important Patterns and Abstraction

### Testing Plan
Unit Tests: Each component (TributaryCluster, Topic, Partition, Producer(/s and subclasses), ConsumerGroup, and Consumer) will have its own suite of unit tests to verify individual functionality and edge cases.

Usability Checklist: Using the command line interface, the checklist will be run through the terminal. The list must test the following functionalities:
- Create Topic: Check can create a new topic and show it in the topic list.
- Creat Message: Test if producers can send messages and if random producers correctly distribute messages across partitions.
- Consume Message: Ensure consumers can retrieve and process messages from their assigned partitions.
- Rebalance Consumer Group: Check the system correctly reallocates partitions among consumers in a group when a new consumer joins or leaves.
- Replay Messages: Test the controlled message replay functionality for a given consumer from a specific offset.

### Development Approach
**Adopting a component-driven approach**: Integrating each component and testing that component using Mockito individually and then testing them together in JUnit to build a coherent and well-designed system. Much better to test overall usability and functionality last while ensuring each component works as intended through the development of this Kafka.

## Final Design Considerations
### Overview of Testing choices
**Usability Tests**
[UsabilityTests.md](usability_tests.md)
- Usability test were my initial form of testing the systems functionality and usability.
- It was a simple way of seeing if the programme worked as I intended it to and if the programme was user friendly and if the commands were easy to understand and use.
- This was the main form of testing before I implemented the JUnit tests and Mockito tests.

**JUnit Tests**
- I've relied extensively on JUnit to validate the Tributary system's integrity and functionality. 
- ensured that each component behaves as expected, whether it's managing messages within topics or processing them through consumers. 
- Verified that all parts of the system integrate seamlessly and perform their roles without issues. 
- The granularity of these tests helps pinpoint where adjustments are necessary
    - provided clear pathway to refine and enhance the system's stability if there where any errors.
- Had an advantage over usability tests as it was more detailed and could test the system in a more controlled environment.
    - Some complications that usability testing couldn't detect but were picked up by JUnit testing suites:
        - IOExceptions were not caught properly
        - Certain files (the JSON files) were not being created properly because the testing environment couldn't reach them
        - Certain commands were not being processed properly (like the consume event command)

**Mockito Testing**
- Mockito was a key step for isolating components and simplifying the testing of complex interactions within the Tributary systems
       - Used in addition to JUnit tests when certain parts of the Tributary cluster5 were unfinished
-  By using mocks, I was able to simulate behaviors of intricate system interactions in isolation, focusing on specific areas without the overhead of full system operations. 
- Was particularly effective for testing the main controller operationg by makign a mock cluster:
    - easier to identify and address potential issues in the architecture.

### Design Patterns and Techniques
**Singleton Pattern for TributaryCluster:**
- To ensure a single instance of the cluster and make it accessible throughout the system.
    - The TributaryCluster serves as the central hub for managing the topics, consumer groups, and producers.
    - Implementing the Singleton Pattern ensures data consistency across the system.

**Abstract Factory Pattern for Component Creation:**
- The TributaryController class had too many responsibilities, violating the Open/Closed Principle, and Typesafety constraints mean that it was very easy for us to overuse switch and if-else statements leading to alot of code duplication and poorly designed code.
    - Abstract Factory Pattern facilitates the creation of different components in the system without specifying the exact class to be created.
    - Simplifies adding new components without modifying existing code.
    - Abstract Factory Pattern for Type Handling, especially when reading from JSON files and creating objects of different types.
        - Ensure Open Closed principle isn't violated because then we can add different types of Object factories and Type handlers (in addition to String and Integer types) for different types of Event and Message Payload.

**Strategy Pattern for Consumer Group Rebalancing:**
- Needed a flexible way to switch between different rebalancing algorithms (Range and Round Robin) at runtime.
    - Implemented the Strategy Pattern to choose the rebalancing algorithm dynamically.
    - Separating rebalancing strategies allows the system to adapt without modifying the core consumer group class.

**Observer Pattern for Event Notification:**
- Required a mechanism to handle changes in topics and consumer groups dynamically.
    - Adding Partitions to a Topic: Consumer groups need to be notified to allocate the new partition.
    - Adding or Deleting Consumers: Triggers rebalancing of partitions across the remaining consumers.
    - Implemented Observer Pattern to ensure that consumers or consumer groups are informed of changes efficiently.

**Concurrency and Generics**
- Implementing Generics for Message class types allows for typesafety while allowing the system to handle different types of messages
    - Important system that might evolve to handle more than 2 types of Event data
- Integrated concurrency into the event processing pipeline to handle parallel message production and consumption
    - Used ExecutorService to manage threads for producing and consuming events simultaneously, ensuring thread-safety and scalability as the system processes multiple events concurrently
    - Synchronization was applied in key areas to prevent race conditions and ensure data integrity during parallel operations
       
### UML Diagram Progression
[1st Draft UML (initial design)](UML_1st_draft.pdf) &rarr; [2nd Draft UML](UML_2nd_draft.pdf) &rarr; [3rd Draft UML](UML_3rd_draft.pdf) &rarr; [4th Draft UML](UML_4th_draft.pdf) &rarr; [final_design.pdf](UML_final_design.pdf)

# Acheivements and Final Reflection
### Acheivements
- Developed an Event-Driven Processing System, emulating Apache Kafka's event streaming capabilities in Java, with parallel data processing and message playback.
- Ensured thread safety and type safety across user threads by utilising Java Generics and Concurrency techniques such as Covariance and Synchronization to safeguard user data during event processing.
- Implemented an RBAC system with token-based authentication, SHA-256 hashing and RSA message encryption to ensure secure role management and confidentiality of data transmitted for producers and consumers.
- Achieved an ~85% test coverage by conducting 75+ JUnit and Mockito test to ensure system reliability.
- Designed a message replay feature that allows Events to be replayed from specific offsets, enabling error checking and correction without compromising the system’s performance.
- Developed a dynamic event rebalancing mechanism, called Range and Round Robin Strategies within Consumer Groups, which honed my skills in algorithmic design
- Integrated key OOP Design patterns such as Singleton & Abstract Factory patterns to compact and synchronize Event production consumption, and Strategy & Observer patterns to streamline Event allocation.
- Conducted extensive usability tests and created a detailed CLI for managing and interacting with the Tributary system, showcasing functionality through a comprehensive set of commands.
- Designed and documented the entire system structure providing a clear overview and understanding of the system’s architecture and seamlessly integrated all these key features to provide users with efficient and useful pipeline for event processing.

**BELOW REFLECTION COMPLETED ON 16/04/2024** &rarr; completed immediately after transferred from GitLab to GitHub for version control
- Overall a very important project that helped me understand the importance of system design as a Backend Software Engineer and the different patterns that can be used to make a system not only more aesthetically pleasing but also more efficient and maintainable.
- Went through a lot of struggles and put in a lot of effort to produce a system that I am proud of.
- I am happy with the final product and the knowledge I have gained from this project.

### Challenges
- The main challenge I had was the time constraint till I had to start studying for my exams and the fact that I was working alone, which made it difficult to get feedback and suggestions on my design.
- I would have liked to have more time to implement and a bit more guidance because I felt at times I was winging it and on my own.
- I also struggled with the planning stage and drawing up the initial UML diagrams and starting this project felt like jumping off a cliff headfirst
    - I didn't know what to do or how to start, I was asking around for tutors' help and I was getting a lot of different answers and *I don't know*'s which wasn't very helpful.
    - I was also struggling with coming up with the correct design patterns I should use and how to implement them in my system.
    - I just went with the flow and started coding and then I realised all it took was a bit of planning and a bit of research and I was steadily making progress.
- Unfortunately, 1 thing I didn't manage to complete in time was J unit testing, which I am disappointed about.
- However, I am proud of the fact that I got through to the end and I will be using this project as a threshold for my ability and motivation in the future.

## Ongoing Updates and Implementations
### Sunday 14/04/2024
**Progress**
- Began by completing the preliminary design for the Tributary system. Created a UML, and although is is very very surface level, it will get much better later on once my design choices and factory patterns have been creat
- Completely understood the basics of the system and the different components that will be involved.
    - had trouble understanding how processing of the messages will be done, but I will be able to figure it out once I have a better understanding of the system.

**Design**
- Currently the design I have in mind is very linear, in that there are no parrterns i have considered
- Current design:
    - Tributary CLI (simple UI) & Tributary System controller (core)
    - Tributary System controller will have the following components:
        - TributaryCluster
        - Topic
        - Partition
        - Producer
        - ConsumerGroup
        - Consumer

**Complications**
- The problem I can already see is that the system is very linear and will not be able to handle the complexity of creating the different Objects. I will need to consider design patterns to make the system more robust and scalable.

### Monday 15/04/2024
**Progress**
- I have started to consider the design patterns that I will be using in the system. I have decided to look at the Factory pattern to create the different  that will be sent through the system. 
    - Will further consult this with a tutor to see if I am on the right track.

### Tuesday 16/04/2024
**Progress**
- Implements all the necessary classes in my API
- Created the TributaryCluster, Topic, Partition, Producer, ConsumerGroup, and Consumer classes
- Created and pretty much finish my TributaryCLI class
- Working on how to implement creation of different components without overloading TributaryController class

### Friday 19/04/2024
**Progress**
- Implement an ObjectFactory, RabalancingStrategy pattern Abstract Classes for Producers and Generics for Message Types
- Pushed my first commit for Task 2
- CLI interface is readily implemented and coming along quite well

**Major Design Choices and Implementations**
- Using a Factory Method and TributaryObject Superclass to create Each object as a way to reduce unnecessary roles and responsibilities for
- Using a Dynamic Behaviour Pattern (Strategy) for Consumer Group rebalancing methods
- Creating an Abstract Producer Class to implement the different types of Producers which have specific produceEvent methods
- Implementing Generics for Message class types

**Further Work**
- Implement error handling and testing for the system
- Implement a more robust and scalable system for the Handling CLI commands
- Implement `create event`, `consume event` (single and Multiple) and also par commands for the CLI
- Implement rebalancing strategies for the Consumer Group
- Implement event allocation methods for Producer class after creating Messages

### Monday 22/04/2024

**Progress**
- Implemented the `create event` and `consume event` commands for the CLI
- Implemented consumption of multiple events from a partition ensuring thread-safe operations and accurate message handling.
- Enhanced the rebalancing strategies (Range and Round Robin) for consumer group partition allocation
    - Used a Rebalancing Strategy Pattern
- Implemented event allocation methods for Producer class after creating Messages


### Thursday 09/05/2024
- Due to a violation of Single Responsibility Principle:
    - Created CLI controller because it made sense to handle the CLI commands in a separate class and not inside the core of the system
        - This is because the core was supposed to handle the inner workings of the system and not the CLI commands
        - The system itself is supposed to be implementable in all sorts of ways, not just through a CLI
    - Created a class to handle the processing of messages

- Singleton Pattern used for TributaryCluster because there should only be one instance of the cluster and it should be accessible from anywhere in the system

- Implemented a Abstract Factory Pattern for the creation of different components in the system
    - The problem was that I had too many responsibilities in the TributaryController class and that was not good for the system
    - Creation methods also violated the Open/Closed Principle as it would be difficult to add to the switch/if - else statements each time a new component was to be added
    - Chose this pattern because it allows for the creation of different types of objects without specifying the exact class of object that will be created
- Implemented a Strategy Pattern for the rebalancing of the Consumer Group
    - Chose this pattern because it allows for the selection of an algorithm at runtime

### Friday 10/05/2024
- Fixed up some bugs in the code:
    - Fixed a bug where the Consumer Group was not being updated correctly after a rebalancing strategy was chosen
    - Fixed a NullPointer Exception that was being thrown when trying to create a topic and the object factory wasn't initialized
    - Fixed a bug where Partition was returning null when trying to consume an event

### Monday 27/05/2024
**Progress**
- Dedicated the day to refining our JUnit test suite, focusing on expanding the coverage to include edge cases and complex interaction scenarios within the Tributary system.
- Identified and resolved several edge cases where message handling did not perform as expected, enhancing the resilience of the system
- Integrated additional test cases to verify the integrity of the message replay feature, ensuring that events are correctly replayed from specified offsets under various system states.
- Optimized test execution times by improving setup and teardown processes in the testing framework.

### Tuesday 28/05/2024
**Progress**
- Conducted a thorough review of Mockito usage across our tests to ensure that all component interactions are adequately simulated, particularly focusing on the interactions between producers, consumers, and the Tributary Cluster.
- Implemented parameterized tests to validate the system's behavior across different configurations and input data sets.
- Enhanced the mock setups to better mimic complex operational flows, including testing the system’s response to simulated failures and network delays.

### Wednesday 29/05/2024
**Progress**
- Focused on exception handling within the Tributary system, particularly how the system manages and logs exceptions during event processing.
- Implemented new tests to verify that the system gracefully handles and recovers from various types of exceptions, such as IOExceptions during event file processing and JSON parsing errors.
- Developed tests to ensure that the system logs detailed information about exceptions, which aids in debugging and maintaining the system
- Tested the robustness of the system under fault conditions by simulating different exception scenarios and observing the system's response to ensure that it does not crash or lose data integrity.

**Future Goals**
- Wish to add the following features in the near future:
    - Implement a more sophisticated logging mechanism to capture detailed system events and performance metrics
    - Enhance the CLI interface to provide more appealing output formats and user-friendly commands
    - Adding play from offset method to allow for message replay
    - Adding parallel functionality so more than 1 event can be consumed at a time


### Tuesday 02/07/2024
**Progress**
- Message Playback with Offset Manipulation: I finally got around to implementing message playback with offset manipulation. This means you can replay events from any point, which makes debugging and testing quite easy.
- Parallel Produce and Consume: Added the ability to produce and consume events in parallel. This was a big one, involving lots of learning and implementing Java's concurrency tools like ExecutorService over simple synchronization.
    - The reason I chose ExecutorService over simple synchronization of certain methods was because this java package allows a program to have multiple instances running simultaneously in something called a thread and the Executor Service package itself ensures these thread do not get mixed up.
    - Simply using synchronisation used alot of resources as it involved creating a new thread and destorying it everytime the function was called.
    - Also using synchronization, the programme limits the execution to one thread at a time. To fix this I would need to manage thread creation and coordination manually, which can be complex and inefficient for larger systems.

- Concurrency and Synchronization: Had to learn Java concurrency, making sure everything was thread-safe. This involved adding synchronized methods in key areas to prevent race conditions which we learns about when doing Singleton pattern in COMP2511.
- Usability Testing: Ran a bunch of usability tests to make sure everything works smoothly. This included testing for edge cases and making sure the system handles errors gracefully.

**Challenges**
- Ensuring that all parameters were correctly validated before processing was tricky. Needed to refine the parsing logic to handle different producer types without errors.
- Thread Safety: Balancing performance and data integrity required careful synchronization of shared resources like topics and partitions.

### Wednesday 03/07/2024
**Progress**
- JUnit Testing: Wrapped up the last of the JUnit tests for the latest program updates. This involved creating tests for the new parallel produce and consume functionalities, as well as the offset manipulation in message playback.
- Thorough Testing: Made sure to cover edge cases, both in JUnit testing and usability testing, to ensure that the system handles all scenarios gracefully. This was crucial to confirm that everything works as expected and that no new bugs were introduced with the recent changes.
    - Becuase of correct and through testing, identified numerous issues and implemented correct fixes to these issues, such as Message creation and parallel event production and consumption
- Type Handler Factory: Added in a TypeHandlerFactory to centralize how type conversions are handled. Each type now has its own handler, like IntegerHandler and StringHandler, taking care of converting JSON values.
    - This setup cuts down on repetitive type checks, making the code way cleaner and more efficient. It's all about modular, maintainable handling for different types.
    - Adding new types is super easy now. Just create a new handler for the type and register it in the factory. The system is flexible and ready to scale with minimal changes.

**Challenges**
- Comprehensive Testing: Implementing the final JUnit tests meant covering a lot of ground, including new concurrency features and message playback. Ensuring thorough and effective tests was a significant task.
- Fixing the type Handing issue: Had to refactor a lot of code to implement the TypeHandlerFactory. This was a big change, but it made the code much cleaner and more maintainable in the long run.

### Wednesday 03/07/2024
**Progress**
- Updated UML Diagram: Completed the UML diagram to reflect the latest updates in methods and class interactions. This was essential to ensure that all recent changes, including concurrency features and message playback, are accurately documented.

**Challenges**
- UML Diagram Updates: Ensuring that the UML diagram accurately reflects the current state of the system required attention to detail and a thorough understanding of all recent changes.


### Monday 02/09/2024
**Future Goals**

Issues to address:
- Optimize space and time efficiency in message processing and storage by using HashMaps instead of Lists of Lists
- Fix the issue with gradle coverage, it says its failing tests but when I run them all of them pass

### Sunday 22/09/2024
- Fixed the issue with gradle coverage, it was previously failing because the Mock Instances were not terminated before the JUnit tests were run. This was fixed by adding a @After method to terminate the Mock Instances after each test.

**Future Goals**

Optimized system and implemented Security features.
- Optimised Event storage using HashMaps for Cluster, Consumer groups, topics and partitions (Messages already use Hasmaps for info storage
- Implemented Message Encryption and Role-Based Access Control (RBAC) for Producers, Consumers and Consumer Groups.

### 01/11/2024
**Progress**
**RBAC**
- Change Producers and Consumer Groups to be outside of Cluster (later)
- Change Cluster from Singleton pattern so multiple can exist (later)
- Make offset property of the partition and not the consumer because partitions assigned to certain consumers can change ✅
- Implement admin Producer and Admin Consumer Group, which allows us to access any partition / topic ✅
  - Tokenization for Admin Producer and Admin Consumer Group ✅
  - Implement verify produce and consume functions ✅
  
- RBAC through error messages. ✅
- Make usability test SETUP for multiple topics, partitions, producers and consumer groups, so I don't waste time in the demonstration ✅

**Encryption**
- Encryption for Message Production and Decryption for message consumption ✅
- Add bytes as a form of payload for messages -> can simulate breaking the encryption using buffer overflow or any other attack ✅
- Use RSA for encryption and decryption ✅
- Created Keys for each message to consumer group or producer, can work well for RBAC ✅

**Future Goals**
- Make first producer and Consumer Group created the admins
- Implement Byte type handling
- Make my system a proper java library
- Add message log for Admin Producer and Admin Consumer Group for added security incase of a breach.
- Implement a mechanism that locks an account or restricts access after a certain number of failed login attempts to prevent brute force attacks.

### 01/11/2024
**Progress**
- Make my system a proper java library. Still Yet to publish
- Completed Blog Restructering
- Started Security Report

**Future Goals**
- Finish Readme Deployment Instructions
- Finish Final UML
- Finish Security Report and add it to the GitHub Security Blog
- Implement TokenManager testing suite.
- Figure out how to make Tributary Cluster a proper Java Library
- Make first producer and Consumer Group created the admins
- Implement Byte type handling
- Implement return Strings instead of stdout (maybe)
- Add message log for Admin Producer and Admin Consumer Group for added security incase of a breach.
- Implement a mechanism that locks an account or restricts access after a certain number of failed login attempts to prevent brute force attacks.

### 02/11/2024 (Goal Planned on 11/09/2024)
Optimized system, Implemented Security features.
- Implemented Message Encryption and Role-Based Access Control (RBAC) for Consumers and Consumer Groups

### 04/11/2024
**Progress**
- Finished Readme Deployment Instructions
- Finish Final UML
- Finish Security Report and add it to the GitHub Security Blog

**Future Goals**
- Implement TokenManager testing suite.
- Figure out how to make Tributary Cluster a proper published Java Library
- Make first producer and Consumer Group created the admins
- Implement Byte type handling
- Implement return Strings instead of stdout (maybe)
- Add message log for Admin Producer and Admin Consumer Group for added security incase of a breach.
- Implement a mechanism that locks an account or restricts access after a certain number of failed login attempts to prevent brute force attacks.

### 29/01/2025
**Future Goals**
- Implement TokenManager testing suite.
- Turn Tributary cluster in to an end-to-end application
    - SCRATCHED THIS IDEA: Figure out how to make Tributary Cluster a proper published Java Library
    - Probably using springboot for this
    - Need to figure out what exactly are the consumers and producers and what is means to send a message to the broker and how is it consumed
    - does this require web sockets? connections to specific IP's? how should I do this, who do I talk to?
    - will i need to change all the outputs in the application to returnable strings instead of something that prints out to the std output?
- Make first producer and Consumer Group created the admins
- Implement Byte type handling
- Implement return Strings instead of stdout (maybe)
- Add message log for Admin Producer and Admin Consumer Group for added security incase of a breach.
- Implement a mechanism that locks an account or restricts access after a certain number of failed login attempts to prevent brute force attacks. maybe?

### 12/02/2025
**Complete this weekend**
- Use Hashmap data structure Arrays 
- Turn Tributary cluster in to an end-to-end application
    - Figure out how to make Tributary Cluster a proper published Java Library (SCRATCHED THIS IDEA!!!!)
    - Probably using springboot for this
    - Consumers and producers will each have a separate instance of such classes 
    - to send a message to the broker will mean for the producer application to send a POST request to the broker which the consumer will either consumer through a Push or Pull based consumption of messages in the broker
    - does this require web sockets? connections to specific IP's? how should I do this, who do I talk to?
    - will i need to change all the outputs in the application to returnable strings instead of something that prints out to the std output? Yes.
- Implement Byte type handling
- Implement return Strings instead of stdout

**Future Goals**
- Make first producer and Consumer Group created the admins
- Add message log for Admin Producer and Admin Consumer Group for added security incase of a breach.
- Implement a mechanism that locks an account or restricts access after a certain number of failed login attempts to prevent brute force attacks. maybe?
- Implement TokenManager testing suite.

### 16/02/2025
**Progress**
1. Go through each function on Controller and change it so that it outputs a string or JSON object instead of printing out result to terminal, apart from the Error messages complete action functions
    - Show Commands (DONE)
    - Delete Commands (DONE)
    - Create Commands (DONE)
    - Update Commands (DONE)
    - Parallel Commands (DONE)
    - Consume Commands (DONE)
    - Fix Rebalancing after adding a partition (DONE)
    - Change parsing event ID as a string to parsing the entire event JSONObject, this is because we used to load the JSON from the working directory (won't work in real life because its an API lol) (DONE)
        - parralel produce and consume needs to change its becuase its kinda wonkey fr:
            - using a parts string that has all the strings of each components IDs which is shi for 
            - Having a tedious check to see if there is a partition depending on whether it is a manual or random producer
        - Changing to an array for each component will be better eg. string[] and JSONArray
3. Change Error Messages to Exceptions (DONE)
    - idk how this will affect the system, maybe I have to catch to catch the error in the Springboot application
    - Substep, I gotta change a few of the UPDATE methods because someof them output a group to demonstrate things like rebalancing and new admins and all that shit but worry abt that after.
    - Substep gotta be able to PARSE JSON object when creating events because functionality and eng requirements states that I have A JSON formated information about the Event
4. Completed `why should i use a message broker article`

**Future Goals**
- Vectorized Topic creationg future implementation -> Parse topicid in create event to existing topic and if not found use vectors to compare and make new one
    - Working on Functionality
    - Could remove creat Topic as a whole as this owuld be automated by vector threshold if doesn't exist by analysing JSON object data (source, content, payload type, etc)
    - Hashmap usage could be justified by iterating through keys (topicIDs) but what if its just a name and doesn't provide enough info for a vector comparison between the event being created and the topic
    - Point of this is to automate setup through the automation of creation methods
- Partition load balancing feature that auto detects excessive load on a topic/consumer group or redundate consumers in a consumer group and this can lead to autocreation of extra partitions in the topic and thus rebalancing the topic for consumer groups depending on their rebalancing strategy
- Fix UML before starting to implement Vector Topic creation automation and load balanced partition creation automation
- Move from JSON create event to producer classes, 100% makes sense
- Make API testing suits and API - CLI interaction Class that calls the API urls and curl them or sum shit


Lower Priority Changes:
- Fix the way the security Keys are stored
    - Possibly initialize them in the Cluster class
- Add Byte-Payload Handling
- Hashmaps of Tributary objects instead of Lists, memory yes higher but efficiency more
    - Lower in priority incase I want vectorise and automate topic creation
    - Possibly better to iterate through Arraylist of Topics rather than Hashmaps
- Make first producer and Consumer Group created the admins
- Add message log for Admin Producer and Admin Consumer Group for added security incase of a breach.
- Implement a mechanism that locks an account or restricts access after a certain number of failed login attempts to prevent brute force attacks. maybe?
- Implement TokenManager testing suite.


### 20/05/2025
**Progress**
1. API interfaces
    - Implementing a HTTP Springboot interface for admin requestions from consumer and producer clients ✅
    - Complete all Exceptions so that Spring can handle them as they bubble up. ✅

    - Implementing a stream-like gRPC connection for message productions and consumption (Tomorrow afternoon)
    - Implement observer pattern for gRPC stream consumers
    - After stream is implemented, implement a gRPC bulk consumer request
    - Make gRPC interface tomorrow ✅
    - Add generics to the proto file and implement JSON into our typehandling if possible ✅

                    ┌─────────────────────────────────────────────────┐
                    │              Spring-Boot Application            │
                    └─────────────────────────────────────────────────┘
        ┌──────────────┐  HTTP/JSON  ┌────────────────┐  in-proc  ┌─────────────┐
        │ Admin REST   │────────────▶│  AdminService  │──────────▶│             │
        │              │             │  (Controller)  │           │             │   
        │ Controller   │             └────────────────┘           │             │
        └──────────────┘                                          │   Core      │
                                                                  │ Tributary   │
        ┌──────────────┐  gRPC stream  ┌────────────────┐         │   Broker    │
        │  gRPC        │──────────────▶│ StreamService  │────────▶│             │
        │ Endpoints    │  (Produce /   │ (Produce /     │ in-proc │             │
        │ (grpc-java)  │   Subscribe)  │  Subscribe)    │         └─────────────┘
        └──────────────┘               └────────────────┘


2. the concurrency changes I need to make
  - Worker thread per topic/connection (up to you)
  - using synchronized write/read functions and thread safe list handling
  - eliminate parallel produce and parallel consume, because if we're gonna get a request in parallel we should do that and not just handle batch requests, batch requests are unlikely to happen in the microservice architecture we're aiming to be a part of
  - I'm not sure whether to use mutex locks or just Collections.synchronized list, design choice is upto you
  - I'm not sure if I have to use complete futures here or not
  - Use either Runnable interface or thread class for the worker thread running in the tributary controller
  - Not sure whether to have 1 thread per action or 1 thread per topic or IDK the relationship right now
  - When Implementing remove number of events to consume and change it to 1 consume per call / observer pattern

3. Purge all implementations of RBAC (tomorrow morning) ✅
    - RBAC implementation is poorly implemented. ✅
    - Replace it later on for actual RBAC implementation using Spring Security - Use Spring AuthService
    - RBAC is a good idea for this project, but should be implemented properly with Java Springboot's actual security features

4. When deleting components of the Tributary system, make sure to delete all relationships
    - SO that objects are not left dangling


### Future Goals:
1. Learn about backpressure and how to implement it
    - Backpressure is a way of controlling the flow of data between producers and consumers in a system. It allows the system to handle situations where the producer is generating data faster than the consumer can process it. This can be done by using techniques such as buffering, flow control, and rate limiting.
    - Apparently i need for when the sytem is under heavy load and the consumer is not able to keep up with the producer. This can lead to data loss or system crashes if not handled properly, especially if data has to be recent becuase of a failure

1. Vectorized Topic creation future implementation -> Parse topicid in create event to existing topic and if not found use vectors to compare and make new one
    - Working on Functionality
    - Hashmap usage could be justified by iterating through keys (topicIDs) but what if its just a name and doesn't provide enough info for a vector comparison between the event being created and the topic
    - Point of this is to automate setup through the automation of creation methods

2. How do I deal with OOM issues when the parition is full
    - 1 way is Partition load balancing feature that auto detects excessive load on a topic/consumer group or redundate consumers in a consumer group and this can lead to autocreation of extra partitions in the topic and thus rebalancing the topic for consumer groups depending on their rebalancing strategy
    - I think i just used disk IO cause we can just store message per line and load it into memory when the offset changesbeyond current partition.
    

- Make API testing suits and API - CLI interaction Class that calls the API urls and curl them or sum shit
    - Create API-connected CLI App acting kind of as a front end that can be used to use the message broker
    - Create UI using admin HTTP connections
    - create domains for both the stream and HTTP JSON connection
    - Set up docker container and host on my linux PC

- Implement on disk message storage
    - Use a file system to store messages and load them into memory when offset changes or is set > 

- Update UML

