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

