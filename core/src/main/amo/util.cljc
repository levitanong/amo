(ns amo.util
  (:require
   [amo.util :include-macros true]))

#?(:clj
   (defmacro time-label
     "Evaluates expr and prints the time it took. Returns `label` and value of expr."
     [label expr]
     `(let [start# (cljs.core/system-time)
            ret# ~expr]
        (prn (cljs.core/str ~label
                            " - "
                            "Elapsed time: "
                            (.toFixed (- (cljs.core/system-time) start#) 6)
                            " msecs"))
        ret#)))