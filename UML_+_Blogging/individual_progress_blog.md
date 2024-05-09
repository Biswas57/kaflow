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




