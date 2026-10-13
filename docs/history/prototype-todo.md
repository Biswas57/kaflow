
1. API interfaces
    - Implementing a HTTP Springboot interface for admin requestions from consumer and producer clients ✅
    - Complete all Exceptions so that Spring can handle them as they bubble up. ✅

    - Implementing a stream-like gRPC connection for message productions and consumption 
    - Implement observer pattern for gRPC stream consumers
    - After stream is implemented, implement a gRPC bulk consumer request
    - Make gRPC interface tomorrow ✅
    - Add generics to the proto file and implement JSON into our typehandling if possible ✅

                    ┌─────────────────────────────────────────────────┐
                    │              Kaflow Architecture                │
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
    - RBAC is a good idea for this project, but should be implemented properly with Java's actual security features

4. Message storage on Disk
    - Implement a file system to store messages on disk
    - Use a file per partition and load messages into memory when the offset changes or is set beyond the current partition
    - This will help with OOM issues when partitions are full
    - Have a worker thread that reads messages from disk and loads them into memory when needed and deletes them when when the messages is older than 30 days or so
    


Low Priority Changes:

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

