# Preliminary Tributary System Design and Implementation Blog

## Design - Task 1
### Analysis of Engineering Requirements
**General Structure**
Tributary Cluster -> Topics -> Partitions -> Messages
Tributary Cluster -> Consumer Groups -> Consumers
Tributary Cluster -> Producers (are their own thing and inside Tributary Cluster)

- **Tributary Cluster:** The top-level component that covers the entire system.
    - **Topics:** Categories or channels within the Tributary Cluster where messages are allocated based on their content or purpose.
        - **Partitions:** Subsets within each topic where messages are stored.
            - Partitions help in distributing the data load and allow for parallel processing.
            - **Producers:** Entities that publish messages to specific partitions within topics.
                **Messages:** Include a timestamp, an identifier, payload type, a key, and the actual data (value).
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
        - In a priority queue style format
- Partition Handling: Each consumer handles one partition at a time but can be assigned to multiple partitions to balance load.

**Consumer Groups**
- Message consumption: Consumer groups consist of multiple consumers working together to process messages from all partitions within a topic.
- Load Balancing: The system distributes partitions among consumers in a group to decrease processing time
    - There are 2 distinct distribution methods

Rebalancing (Message Distribution) Strategies:
- Range Rebalancing: Partitions are evenly divided among consumers, with adjustments made for odd counts.
- Round Robin Rebalancing: Partitions are allocated in a rotating manner to ensure even distribution of workload among consumers.

**Message Replay**
- Messages from a certain offset onwards are processed, until the most recent message at the latest offset is reached
- Controlled Replay: Consumers can replay messages from a specific offset to catch errors and failures or for processing historical data. 
    - This feature is crucial for when a system feature may not be working as it's supposed to.

**Stuff from the Lectures**
Concurrency:
- Thread Safety: To handle concurrent operations without issues, the system will use synchronization

Generics:
- Type Flexibility: The system uses Java generics to handle messages of any type, making the system able to adapt to different data types without duplicating code.

### Initial UML Diagram

[initial_design.pdf](initial_design.pdf)

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
- Basically testing each component individually and then integrating them together to form the final system. Much better to test overall usability and functionality.

## Video Demo - Task 2
[Youtube Video](https://youtu.be/KzwQWZhSc6M)

## Final Design and Reflection - Task 3
### Final Usability Test List
[UsabilityTests.md](usability_tests.md)

### Overview of Design Patterns Used
-  The Factory Method pattern in creating objects like topics, consumers, producers, and partitions helps centralize creation logic in the ObjectFactory class.
    - reduces the responsibilities of the TributaryController
    - allowing code to adhering to the Single Responsibility Principle
- For the rebalancing of partititons across consumers in the groups, the Strategy pattern was implemented
    - allows the system to switch between different rebalancing algorithms (Range and Round Robin) at runtime
    - By having the rebalancing in separate strategy classes, the system can adjust its behavior without modifying the actual consumer group class to use the rebalancing feature
- The observer Pattern was used for the following situations:
    - Adding artitions to a topic (consumer group had to allocate said partition) 
    - when a consumer was added or deleted (rebalancing of partitions occured)

### Design Considerations
- Created an Abstract Producer Class to implement the different types of Producers which have specific produceEvent methods
    - Each specific producer subclass implements its own allocateMessage method but the super class handle the produceMessage method to reduce code duplication
- Implementing Generics for Message class types allows for typesafety while allowing the system to handle different types of messages
    - Important system that might evolve to handle more than 2 types of Event data

### Final UML Diagram
[final_design.pdf](final_design.pdf)

### Reflection
[Ongoing Reflection](individual_progress_blog.md)

**Final Reflection**
- Overall a very important project that helped me understand the importance of system design and the different patterns that can be used to make my system not only more aesthetically pleasing but also more efficient and maintainable.
- Went through a lot of struggles and put in a lot of effort to produce a system that I am proud of.
- I am happy with the final product and the knowledge I have gained from this project.

**Challenges**
- Main hallenge was the time constraint and the fact that I was working alone, which made it difficult to get feedback and suggestions on my design.
- I would have liked to have more time to implement and a bit more guidance because I really felt at times I was just winging it and on my own.
- I also struggled with the UML diagrams and actually starting this project (felt like jumping off a cliff headfirst)
- Unfortunately 1 thing I didn't manage to complete in time was J unit testing, which I am disappointed about.

- However I am proud of the fact that I got through to the end, with the help of the ed forum, and I will be using this project as a threshold for my ability and motivation in the future.
