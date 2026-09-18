// KOTRAIL_CONFIG: rules.compose.nesting=on
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

// Default limit: 5. Depth counts composable calls nested through composable content lambdas.

// Depth 5: allowed.
@Composable
fun AtLimit() {
    Column {                       // 1
        Box {                      // 2
            Column {               // 3
                Box {              // 4
                    Text("five")   // 5
                }
            }
        }
    }
}

// Depth 6: the first call past the limit is reported, nothing deeper.
@Composable
fun PastLimit() {
    Column {                                   // 1
        Box {                                  // 2
            Column {                           // 3
                Box {                          // 4
                    Column {                   // 5
                        <!KOTRAIL_COMPOSABLE_NESTING_TOO_DEEP!>Box<!> {   // 6: reported
                            Text("seven")      // 7: not reported again
                        }
                    }
                }
            }
        }
    }
}

// LazyColumn's content lambda is not composable, so it adds no depth; items' slot does.
@Composable
fun LazyList() {
    Column {                                   // 1
        Box {                                  // 2
            LazyColumn {                       // 3
                items(3) {
                    Column {                   // 4
                        Text("item")           // 5
                    }
                }
            }
        }
    }
}

// remember's lambda is not a composable slot either.
@Composable
fun WithRemember() {
    Column {                                   // 1
        val label = remember { "x" }
        Box {                                  // 2
            Column {                           // 3
                Box {                          // 4
                    Text(label)                // 5
                }
            }
        }
    }
}

// Sibling subtrees restart from the depth of their parent.
@Composable
fun Siblings() {
    Column {                                   // 1
        Box { Text("a") }                      // 2, 3
        Box { Text("b") }                      // 2, 3
        Column {                               // 2
            Box {                              // 3
                Box {                          // 4
                    Text("c")                  // 5
                }
            }
        }
    }
}

// Extracting the deep part into its own composable resets the count.
@Composable
fun Extracted() {
    Column {                                   // 1
        Box {                                  // 2
            Column {                           // 3
                Box {                          // 4
                    Inner()                    // 5
                }
            }
        }
    }
}

@Composable
fun Inner() {
    Column {                                   // 1
        Box {                                  // 2
            Text("inner")                      // 3
        }
    }
}

/* GENERATED_FIR_TAGS: functionDeclaration, integerLiteral, lambdaLiteral, localProperty, propertyDeclaration,
stringLiteral */
