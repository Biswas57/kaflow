# Indivdual Blog Posts for Design and Complications

## Sunday 14/04/2024
### Progress
- Began by completing the preliminary design for the Tributary system. Created a UML, and although is is very very surface level, it will get much better later on once my design choices and factory patterns have been creat
- Completely understood the basics of the system and the different components that will be involved.
    - had trouble understanding how processing of the messages will be done, but I will be able to figure it out once I have a better understanding of the system.

### Design
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

### Complications
- The problem I can already see is that the system is very linear and will not be able to handle the complexity of creating the different Objects. I will need to consider design patterns to make the system more robust and scalable.

## Monday 15/04/2024
### Progress
- I have started to consider the design patterns that I will be using in the system. I have decided to look at the Factory pattern to create the different  that will be sent through the system. 
    - Will further consult this with a tutor to see if I am on the right track.

## Tuesday 16/04/2024
### Progress
- Implements all the necessary classes in my API
- Created the TributaryCluster, Topic, Partition, Producer, ConsumerGroup, and Consumer classes
- Created and pretty much finish my TributaryCLI class
- Working on how to implement creation of different components without overloading TributaryController class

## Friday 19/04/2024
### Progress
- Implement an ObjectFactory, RabalancingStrategy pattern Abstract Classes for Producers and Generics for Message Types
- Pushed my first commit for Task 2
- CLI interface is readily implemented and coming along quite well

### Major Design Choices and Implementations
- Using a Factory Method and TributaryObject Superclass to create Each object as a way to reduce unnecessary roles and responsibilities for
- Using a Dynamic Behaviour Pattern (Strategy) for Consumer Group rebalancing methods
- Creating an Abstract Producer Class to implement the different types of Producers which have specific produceEvent methods
- Implementing Generics for Message class types

### Further Work
- Implement error handling and testing for the system
- Implement a more robust and scalable system for the Handling CLI commands
- Implement `create event`, `consume event` (single and Multiple) and also par commands for the CLI
- Implement rebalancing strategies for the Consumer Group
- Implement event allocation methods for Producer class after creating Messages

## Monday 22/04/2024

### Progress
- Implemented the `create event` and `consume event` commands for the CLI
- Implemented consumption of multiple events from a partition ensuring thread-safe operations and accurate message handling.
- Enhanced the rebalancing strategies (Range and Round Robin) for consumer group partition allocation
    - Used a Rebalancing Strategy Pattern
- Implemented event allocation methods for Producer class after creating Messages


## Thursday 09/05/2024
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

## Friday 10/05/2024
- Fixed up some bugs in the code:
    - Fixed a bug where the Consumer Group was not being updated correctly after a rebalancing strategy was chosen
    - Fixed a NullPointer Exception that was being thrown when trying to create a topic and the object factory wasn't initialized
    - Fixed a bug where Partition was returning null when trying to consume an event

## Monday 27/05/2024
**Progress**
- Dedicated the day to refining our JUnit test suite, focusing on expanding the coverage to include edge cases and complex interaction scenarios within the Tributary system.
- Identified and resolved several edge cases where message handling did not perform as expected, enhancing the resilience of the system
- Integrated additional test cases to verify the integrity of the message replay feature, ensuring that events are correctly replayed from specified offsets under various system states.
- Optimized test execution times by improving setup and teardown processes in the testing framework.

## Tuesday 28/05/2024
**Progress**
- Conducted a thorough review of Mockito usage across our tests to ensure that all component interactions are adequately simulated, particularly focusing on the interactions between producers, consumers, and the Tributary Cluster.
- Implemented parameterized tests to validate the system's behavior across different configurations and input data sets.
- Enhanced the mock setups to better mimic complex operational flows, including testing the system’s response to simulated failures and network delays.

## Wednesday 29/05/2024
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


## Tuesday 02/07/2024
**Progress**
- Message Playback with Offset Manipulation: I finally got around to implementing message playback with offset manipulation. This means you can replay events from any point, which makes debugging and testing quite easy.
- Parallel Produce and Consume: Added the ability to produce and consume events in parallel. This was a big one, involving lots of learning and implementing Java's concurrency tools like ExecutorService and synchronization.
- Concurrency and Synchronization: Had to learn Java concurrency, making sure everything was thread-safe. This involved adding synchronized blocks in key areas to prevent race conditions which we learns about when doing Singleton pattern in COMP2511.
- Usability Testing: Ran a bunch of usability tests to make sure everything works smoothly. This included testing for edge cases and making sure the system handles errors gracefully.
**Challenges**
- Ensuring that all parameters were correctly validated before processing was tricky. Needed to refine the parsing logic to handle different producer types without errors.
- Thread Safety: Balancing performance and data integrity required careful synchronization of shared resources like topics and partitions.

## Wednesday 03/07/2024
**Progress**
- Updated UML Diagram: Completed the UML diagram to reflect the latest updates in methods and class interactions. This was essential to ensure that all recent changes, including concurrency features and message playback, are accurately documented.
- JUnit Testing: Wrapped up the last of the JUnit tests for the latest program updates. This involved creating tests for the new parallel produce and consume functionalities, as well as the offset manipulation in message playback.
- Thorough Testing: Made sure to cover edge cases, both in JUnit testing and usability testing, to ensure that the system handles all scenarios gracefully. This was crucial to confirm that everything works as expected and that no new bugs were introduced with the recent changes.

**Challenges**
- UML Diagram Updates: Ensuring that the UML diagram accurately reflects the current state of the system required attention to detail and a thorough understanding of all recent changes.
- Comprehensive Testing: Implementing the final JUnit tests meant covering a lot of ground, including new concurrency features and message playback. Ensuring thorough and effective tests was a significant task.