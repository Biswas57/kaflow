# Tributary Cluster Design and Implementation Blog

### Overview of Engineering Requirements
Modern software often relies on many small programs, called microservices, that work together to provide a complete application or service. Event-driven architecture makes up much of the backbone of modern software which microservices rely upon to run smoothly. In essence, event-driven architecture is similar to a signalling system that allows different parts of a software system to work independently but remain responsive to events at scalable rates. This makes the whole system faster, more flexible, and able to handle more tasks at once without getting stuck waiting for each part to finish its job.

This library is based on a heavily simplified version of the event streaming infrastructure [Apache Kafka](https://kafka.apache.org/). A quick read of Kafka's design and purpose is recommended to understand the basis and workings of this project, a brief video to understand what they do can be found [here](https://youtu.be/vHbvbwSEYGo).

The fundamental premise on which Event-Driven Architecture rests is the ability of producer and consumer entities in the system to share data asynchronously via a stream-like channel, in other words a Tributary-like platform. However, our library allowed for more complex interactions than simply that of a single channel. Some of my key achievements throughout this project were:

- Constructed a **Tributary Event Processing System**, emulating Apache Kafka's Event Processing Platform in Java, with a function CLI, parallel data processing and assured type-safety by implementing Concurrency and Generics alongside important OOP Design principles.
- Integrated key OOP Design patterns such as Singleton & Abstract Factory patterns to compact and synchronize Event production consumption, and Strategy & Observer patterns to streamline Event allocation.
- Ensured thread safety across concurrent producer and consumer threads to protect user data in the event processing pipeline by utilizing Java concurrency tools such as synchronization and ExecutorService.
- Achieved an 85% test coverage by conducting ∼75 tests in JUnit and Mockito to ensure the system’s reliability.
- Designed a message replay feature that allows Events to be replayed from specific offsets, enabling error checking and correction without compromising the system’s performance.
- Developed a dynamic event rebalancing mechanism, called Range and Round Robin Strategies within Consumer Groups, which honed my skills in algorithmic design
- Conducted extensive usability tests and created a detailed CLI for managing and interacting with the Tributary system, showcasing functionality through a comprehensive set of commands.
- Designed and documented the entire system structure providing a clear overview and understanding of the system’s architecture and seamlessly integrated all these key features to provide users with efficient and useful pipeline for event processing.


## Project Overview
**NOTE:** A complete visual overview of the entire system exist, showcasing the system in the [final UML Diagram](Blogging&Design/final_design.pdf) and a more extensive overview of the capabilities, constrains and functionality of the system can be found in the [Engineering Requirements](Engineering_Requirements.md)

**General Structure of System**
1. Content Structure:
       Tributary Cluster -> Topics -> Partitions -> Messages.
2. Consumer Structure:
       Tributary Cluster -> Consumer Groups -> Consumers.
4. Producer Structure:
       Tributary Cluster -> Producers.

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
Rebalancing (Message Distribution) Strategies:
- Range Rebalancing: Partitions are evenly divided based on the number of consumers, with adjustments made for odd counts.
- Round Robin Rebalancing: Partitions are allocated in a rotating manner to ensure even distribution of workload among consumers.

**Synchronous Data Processing**
- Concurrency: Java synchronization to manage parallel message handling,
       - ensuring thread-safe operations across producer and consumer threads for real-time data integrity in the event processing pipeline.

**Message Replay**
Messages from a certain offset onwards are processed, until the most recent message at the latest offset is reached
- Controlled Replay: Consumers can replay messages from a specific offset to catch errors and failures or for processing historical data.
       - This feature is crucial for when a system feature may not be working as it's supposed to.

![](/images/controlledReplay.png)
> ℹ  NOTE: The above image demonstrates a consumer starting at offset 6 that performed normal consumption until offset 9. This consumer then triggered a
> controlled replay from offset 4 that played back all the messages from that offset until the most recently consumed message (i.e messages 6, 7, 8 and 9
> were consumed again).

### Design Considerations

Concurrency:
- Thread Safety: To handle concurrent operations without issues, the system will use synchronization via the Singleton Pattern for the Tributary Cluster.

Generics:
- Type Flexibility: The system uses Java generics to handle messages of any type, making the system able to adapt to different data types without duplicating code.
    - This will be implemented using the Factory Pattern to create Objects of differing types.

### Initial UML Diagram

[initial_design.pdf](Blogging&Design/initial_design.pdf)

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

### Usability Test Checklist
- Create Topic: Check can create a new topic and show it in the topic list.
- Creat Message: Test if producers can send messages and if random producers correctly distribute messages across partitions.
- Consume Message: Ensure consumers can retrieve and process messages from their assigned partitions.
- Rebalance Consumer Group: Check the system correctly reallocates partitions among consumers in a group when a new consumer joins or leaves.
- Replay Messages: Test the controlled message replay functionality for a given consumer from a specific offset.

### Testing Plan
Unit Tests: Each component (TributaryCluster, Topic, Partition, Producer(/s and subclasses), ConsumerGroup, and Consumer) will have its own suite of unit tests to verify individual functionality and edge cases.

Usability Checklist: Using the command line interface, the checklist will be run through the terminal.

### Development Approach
**Adopting a component-driven approach**
- Basically integrating each components individually and then testing them together to form the final system. Much better to test overall usability and functionality.

## Final Design and Reflection

### Overview of Testing choices
**Usability Tests**
[UsabilityTests.md](Blogging&Design/usability_tests.md)
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
    - Some things that were picked up by the JUnit tests were:
        - IOExceptions were not caught properly
        - Certain files (the JSON files) were not being created properly because the testing environment couldn't reach them
        - Certain commands were not being processed properly (like the consume event command)

**Mockito Testing**
- Mockito was a key step for isolating components and simplifying the testing of complex interactions within the Tributary systems
       - Used in addition to JUnit tests when certain parts of the Tributary cluster5 were unfinished
-  By using mocks, I was able to simulate behaviors of intricate system interactions in isolation, focusing on specific areas without the overhead of full system operations. 
- Was particularly effective for testing the main controller operationg by makign a mock cluster:
    - easier to identify and address potential issues in the architecture.

### Overview of Design Patterns Used
**Singleton Pattern for TributaryCluster:**
- **Reason:** To ensure a single instance of the cluster and make it accessible throughout the system.
- **Details:**
    - The TributaryCluster serves as the central hub for managing the topics, consumer groups, and producers.
    - Implementing the Singleton Pattern ensures data consistency across the system.

**Abstract Factory Pattern for Component Creation:**
- **Reason:** The TributaryController class had too many responsibilities, violating the Open/Closed Principle, and Typesafety constraints mean that it was very easy for us to overuse switch and if-else statements leading to alot of code duplication and poorly designed code.
- **Details:**
    - Abstract Factory Pattern facilitates the creation of different components in the system without specifying the exact class to be created.
    - Simplifies adding new components without modifying existing code.
    - Abstract Factory Pattern for Type Handling, especially when reading from JSON files and creating objects of different types.
        - Ensure Open Closed principle isn't violated because then we can add different types of Object factories and Type handlers (in addition to String and Integer types) for different types of Event and Message Payload.

**Strategy Pattern for Consumer Group Rebalancing:**
- **Reason:** Needed a flexible way to switch between different rebalancing algorithms (Range and Round Robin) at runtime.
- **Details:**
    - Implemented the Strategy Pattern to choose the rebalancing algorithm dynamically.
    - Separating rebalancing strategies allows the system to adapt without modifying the core consumer group class.

**Observer Pattern for Event Notification:**
- **Reason:** Required a mechanism to handle changes in topics and consumer groups dynamically.
- **Details:**
    - Adding Partitions to a Topic: Consumer groups need to be notified to allocate the new partition.
    - Adding or Deleting Consumers: Triggers rebalancing of partitions across the remaining consumers.
    - Implemented Observer Pattern to ensure that consumers or consumer groups are informed of changes efficiently.

### Design Considerations
- Created an Abstract Producer Class to implement the different types of Producers which have specific produceEvent methods
    - Each specific producer subclass implements its own allocateMessage method but the super class handle the produceMessage method to reduce code duplication
- Implementing Generics for Message class types allows for typesafety while allowing the system to handle different types of messages
    - Important system that might evolve to handle more than 2 types of Event data

### Final UML Diagram
[final_design.pdf](Blogging&Design/final_design.pdf)

### Reflection
[Ongoing Reflection](Blogging&Design/ongoing_progress_blog.md)

**Final Reflection**
- Overall a very important project that helped me understand the importance of system design as a Backend Software Engineer and the different patterns that can be used to make a system not only more aesthetically pleasing but also more efficient and maintainable.
- Went through a lot of struggles and put in a lot of effort to produce a system that I am proud of.
- I am happy with the final product and the knowledge I have gained from this project.

**Challenges**
- Main challenge was the time constraint I had till I had to start studying for my exams and the fact that I was working alone, which made it difficult to get feedback and suggestions on my design.
- I would have liked to have more time to implement and a bit more guidance because I really felt at times I was just winging it and on my own.
- I also struggled with the planning stage and drawing up the initila UML diagrams and actually starting this project (felt like jumping off a cliff headfirst)
    - Didn't know what to d oor how to start, I was asking around for tutors' help and I was getting a lot of different answers and I was getting confused.
    - I was also struggling with coming up with the correct design patterns i should use and how to implement them in my system.
    - I just went with the flow and started coding and then I realised all it took was a bit of planning and a bit of research and I was steadily making progress.
- Unfortunately 1 thing I didn't manage to complete in time was J unit testing, which I am disappointed about.
    - **Edit:** Have now implemented extensive JUnit and Mockito tests from 28/05/2024
    - **2nd Edit:** Have also offset replay and parallel event management and running tests for these functions from 02/07/2024.
    - **3rd Edit:** Optimised Event storage using HashMaps and fixed gradle coverage issue 02/07/2024
- However I am proud of the fact that I got through to the end and I will be using this project as a threshold for my ability and motivation in the future.
