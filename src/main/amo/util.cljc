(ns amo.util)

#?(:clj
   (defmacro time-label
     "Evaluates expr and prints the time it took. Returns `label` and value of expr."
     [label expr]
     `(let [start# (system-time)
            ret# ~expr]
        (prn (cljs.core/str ~label
                            " - "
                            "Elapsed time: "
                            (.toFixed (- (system-time) start#) 6)
                            " msecs"))
        ret#)))