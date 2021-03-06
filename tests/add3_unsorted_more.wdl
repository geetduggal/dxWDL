# A simple workflow with two stages wired together.
# It is supposed to sum three integers.
# This one is a variant that is has a forward reference
# First and very simple test of topological sorting
task add3_Add {
    Int a
    Int b

    command {
        echo $((a + b))
    }
    output {
        Int sum = a + b
    }
}


# Topo ordering add3_Add, Add3, Add3More, Add3Final
workflow add3 {
    Int ai
    Int bi
    Int ci

    call add3_Add as Add3 {
         input: a = add3_Add.sum, b = ci
    }

    call add3_Add as Add3Final {
         input: a = Add3More.sum, b = Add3More.sum
    }

    call add3_Add as Add3More {
        input: a = Add3.sum, b = add3_Add.sum
    }

    call add3_Add {
         input: a = ai, b = bi
    }

    output {
        Add3.sum
    }
}
