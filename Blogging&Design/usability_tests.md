# Tributary CLI inputs

## Test 1: With 1 of Every Command
### Creating a String Topic in Tributary Cluster
create topic banana string

### Creating a Partition in banana Topic
create partition banana bananaCookingMethods

### Creating a Producer with Random Allocation
create producer bananaBoiler banana random

### Creating a Consumer Group with Range Rebalancing
create consumer group bananaChefs banana range

### Creating Consumer
create consumer bananaChefs beginnerChef

### Creating Consumer for Deletion Purposes
create consumer bananaChefs deleteBeginnerChef

### Show Consumer Groups to check Creation
show consumer group bananaChefs

### Delete Consumer (Shows Consumer Groups)
delete consumer deleteBeginnerChef

### Creating a messages in the Banana Topic
create event bananaBoiler banana boilBanana bananaCookingMethods

### Show Topic to check Creation
show topic banana

### Consume a Message in the Banana Topic
consume event beginnerChef bananaCookingMethods


## Test 2: With Integer Type
### Creating a String Topic in Tributary Cluster
create topic banana integer

### Creating a Partition in banana Topic
create partition banana bananaCookingMethod1

### Creating a Partition in banana Topic
create partition banana bananaCookingMethod2

### Creating a Partition in banana Topic
create partition banana bananaCookingMethod3

### Creating a Partition in banana Topic
create partition banana bananaCookingMethod4

### Show entire Topic for Creation
show topic banana

## With Manual and Random Allocation
### Creating a Producer with Random Allocation
create producer bananaBoiler banana random

### Creating a Producer with Manual Allocation (Integer has been set with capital ‘i’)
create producer bananaFrier banana manual

## With Range and Round Robin Rebalancing
### Creating a Consumer Group with Range Rebalancing
create consumer group bananaChefs banana range

### Creating Consumer
create consumer bananaChefs beginnerChef1

### Creating Consumer
create consumer bananaChefs beginnerChef2

### Creating Consumer
create consumer bananaChefs beginnerChef3

### Show Consumer Group for correct rebalancing
show consumer group bananaChefs

### Updating the rebalancing of Consumer Group
update consumer group rebalancing bananaChefs roundRobin

### Show Consumer Group for correct rebalancing
show consumer group bananaChefs

### Creating a message in the Banana Topic (Producer with Random Allocation)
create event bananaBoiler banana bananaBoilingNums

### Creating a messages in the Banana Topic (Producer with Random Allocation)
create event bananaBoiler banana bananaBoilingTemp

### Creating a messages in the Banana Topic (Producer with Random Allocation)
create event bananaBoiler banana bananaBoilingDur

### Creating a message in the Banana Topic
create event bananaFrier banana bananaFryNums bananaCookingMethod2

### Creating a message in the Banana Topic
create event bananaFrier banana bananaFryTemp bananaCookingMethod3

### Creating a message in the Banana Topic
create event bananaFrier banana bananaFryDur bananaCookingMethod4

### Show Consumer Group and Topic so I know Message to Partition Relation and Partition to Consumer Relation (Gets a lil blurry here)
show topic banana
show consumer group bananaChefs

//
**If none of the Partitions have only have one message then do the next 2 commands**
### Creating a Producer with Manual Allocation (Just to test singular consume event)
create producer bananaCook banana manual

### Creating a message in the Banana Topic
create event bananaCook banana bananaCookForFun bananaCookingMethod1
//

### Consume a single Message in the Banana Topic
consume event beginnerChef1 bananaCookingMethod4

### Error Consume Event (Partition not allocated to Consumer)
consume event beginnerChef1 bananaCookingMethod1

### Consume all events in the Banana Topic
consume events beginnerChef2 bananaCookingMethod2 (whatever number is in it)

### Consume multiple Messages in the Banana Topic
consume events beginnerChef3 bananaCookingMethod3 (number of messages in partition - 1)

### Consume a Message in bananaCookingMethod2 where there’s none left to consume
consume event beginnerChef2 bananaCookingMethod2

### Consume last Message in bananaCookingMethod3
consume event beginnerChef3 bananaCookingMethod3

### Playback with 2 of the Partitions for Consumer 1
update consumer offset beginnerChef1 bananaCookingMethod1 1
update consumer offset beginnerChef1 bananaCookingMethod4 0

### Parrallel Produce 2 new messages for the 2 partitions
parrallel produce bananaFrier banana bananaFryNums bananaCookingMethod1 bananaFrier banana bananaFryNums bananaCookingMethod4

### Show Topic to check Creation and check new offsets
show topic banana

### Parallel Consume from the playback to the end of both partitions
parallel consume beginnerChef1 bananaCookingMethod1 [number of messages left] beginnerChef1 bananaCookingMethod4 [number of messages left]
