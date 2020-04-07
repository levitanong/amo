(ns amo.protocols)

(defprotocol ITransact
  (-transact! [this mutations]
    "Adds mutations to a global tx-queue, and schedule the execution of that queue
     based on `schedule-fn`'s discretion."))

(defprotocol ISchedule
  (-schedule! [this f]
    "If there is a pending schedule, clear it, then schedule f for execution.
     Relies on the fact that `schedule-fn` returns an ID, and `release-fn` takes an ID
     to clear a pending schedule."))

(defprotocol IPublisher
  (-add-subscriber! [this subscriber]
    "A method to be called by a component to subscribe to changes in state.
     subscriber must at least have the following keys 
     `#{:subscriber/id :subscriber/read-keys :subscriber/rerender}`")
  (-remove-subscriber! [this id]
    "Stop listening to state changes"))

(defprotocol AmoApp
  (-amo-app? [this]))