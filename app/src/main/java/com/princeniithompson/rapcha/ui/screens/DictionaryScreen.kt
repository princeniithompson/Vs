package com.princeniithompson.rapcha.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.princeniithompson.rapcha.data.CustomVocabularyRepository
import com.princeniithompson.rapcha.data.VocabClass
import com.princeniithompson.rapcha.data.VocabClassifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val vocabularyList by CustomVocabularyRepository.vocabulary.collectAsState()
    val vocabularyItems by CustomVocabularyRepository.vocabularyItems.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    var showAddDialog by remember { mutableStateOf(false) }
    var wordToAdd by remember { mutableStateOf("") }
    var spokenAliasToAdd by remember { mutableStateOf("") }
    var addClassChoice by remember { mutableStateOf(ClassChoice.AUTO) }
    var addError by remember { mutableStateOf<String?>(null) }

    var editingWord by remember { mutableStateOf<String?>(null) }
    var editedWordValue by remember { mutableStateOf("") }
    var editAlias by remember { mutableStateOf("") }
    var editClassChoice by remember { mutableStateOf(ClassChoice.AUTO) }
    var editError by remember { mutableStateOf<String?>(null) }

    val filteredList = remember(vocabularyList, searchQuery) {
        if (searchQuery.isBlank()) {
            vocabularyList
        } else {
            vocabularyList.filter { it.contains(searchQuery.trim(), ignoreCase = true) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Top App Bar
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "|||| Rapcha",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = "Menu",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Search Bar (Rounded outlined field)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("dictionary_search_input"),
                placeholder = {
                    Text(
                        text = "Search",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 15.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Soft Limit Warning (> 100 terms)
            if (vocabularyList.size > 100) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Info",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "You have ${vocabularyList.size} terms. For best recognition speed with Gemini Live, keeping under 100 terms is recommended.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }

            // Clean-up Suggestions Section (Dedicated section right above word list)
            val cleanupSuggestions by CustomVocabularyRepository.cleanupSuggestions.collectAsState()
            val context = androidx.compose.ui.platform.LocalContext.current

            AnimatedVisibility(
                visible = cleanupSuggestions.isNotEmpty() && searchQuery.isBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .testTag("dictionary_cleanup_section"),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteSweep,
                                    contentDescription = "Clean-up Suggestions",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Clean-up Suggestions (${cleanupSuggestions.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "You haven’t used these terms in 60+ days. Remove them to keep your list lean?",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        cleanupSuggestions.forEach { suggestion ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp)
                                ) {
                                    Text(
                                        text = "You haven’t used ‘${suggestion.term}’ in a while. Remove it to free space?",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        androidx.compose.material3.OutlinedButton(
                                            onClick = {
                                                CustomVocabularyRepository.keepTerm(suggestion.term)
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Kept '${suggestion.term}' in dictionary",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            modifier = Modifier
                                                .height(38.dp)
                                                .testTag("cleanup_keep_${suggestion.term}"),
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Text(
                                                text = "Keep",
                                                style = MaterialTheme.typography.labelLarge
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(8.dp))

                                        androidx.compose.material3.Button(
                                            onClick = {
                                                CustomVocabularyRepository.deleteTerm(suggestion.term)
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "Removed '${suggestion.term}' from dictionary",
                                                    android.widget.Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            modifier = Modifier
                                                .height(38.dp)
                                                .testTag("cleanup_remove_${suggestion.term}"),
                                            shape = RoundedCornerShape(12.dp),
                                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        ) {
                                            Text(
                                                text = "Remove",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Word List Card Container (White grouped card with rounded corners)
            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = if (searchQuery.isNotBlank()) "No words matching \"$searchQuery\"" else "No words in your dictionary yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap the + button below to add custom names, specialized terms, or email addresses.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = filteredList,
                            key = { _, item -> item }
                        ) { index, term ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val existing = vocabularyItems.firstOrNull {
                                            it.term.equals(term, ignoreCase = true)
                                        }
                                        editingWord = term
                                        editedWordValue = term
                                        editAlias = existing?.spokenAlias.orEmpty()
                                        editClassChoice = classChoiceFor(existing)
                                        editError = null
                                    }
                                    .padding(horizontal = 20.dp, vertical = 18.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = term,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    val item = vocabularyItems.firstOrNull {
                                        it.term.equals(term, ignoreCase = true)
                                    }
                                    val rowNote = dictionaryRowNote(item)
                                    if (rowNote != null) {
                                        Text(
                                            text = rowNote,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (index < filteredList.size - 1) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                    thickness = 0.8.dp,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp)) // Headroom for FAB and Bottom Bar
        }

        // Floating Action Button (+) on Bottom Right
        FloatingActionButton(
            onClick = {
                wordToAdd = ""
                spokenAliasToAdd = ""
                addClassChoice = ClassChoice.AUTO
                addError = null
                showAddDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 24.dp)
                .size(56.dp)
                .testTag("add_word_fab"),
            shape = CircleShape,
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Add word",
                modifier = Modifier.size(24.dp)
            )
        }
    }

    // Add Word Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = "Add word",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    WordAndAliasFields(
                        spelling = wordToAdd,
                        onSpelling = {
                            wordToAdd = it
                            addError = null
                        },
                        alias = spokenAliasToAdd,
                        onAlias = {
                            spokenAliasToAdd = it
                            addError = null
                        },
                        spellingTag = "add_word_text_field",
                        aliasTag = "add_word_alias_field",
                        classChoice = addClassChoice,
                        onClassChoice = {
                            addClassChoice = it
                            addError = null
                        }
                    )
                    if (addError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = addError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = wordToAdd.trim()
                        if (trimmed.isBlank()) {
                            addError = "Please enter a word or phrase"
                            return@TextButton
                        }
                        val aliasError = VocabClassifier.aliasError(spokenAliasToAdd)
                        if (aliasError != null) {
                            addError = aliasError
                            return@TextButton
                        }
                        val success = CustomVocabularyRepository.addTerm(
                            term = trimmed,
                            spokenAlias = spokenAliasToAdd,
                            classOverride = overrideFor(addClassChoice)
                        )
                        if (success) {
                            showAddDialog = false
                        } else {
                            addError = "Word already exists in dictionary"
                        }
                    },
                    modifier = Modifier.testTag("add_word_confirm_button")
                ) {
                    Text(
                        text = "Add",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Edit / Delete Word Dialog
    if (editingWord != null) {
        val targetWord = editingWord!!
        AlertDialog(
            onDismissRequest = { editingWord = null },
            title = {
                Text(
                    text = "Edit word",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    WordAndAliasFields(
                        spelling = editedWordValue,
                        onSpelling = {
                            editedWordValue = it
                            editError = null
                        },
                        alias = editAlias,
                        onAlias = {
                            editAlias = it
                            editError = null
                        },
                        spellingTag = "edit_word_text_field",
                        aliasTag = "edit_word_alias_field",
                        classChoice = editClassChoice,
                        onClassChoice = {
                            editClassChoice = it
                            editError = null
                        }
                    )
                    if (editError != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = editError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(
                        onClick = {
                            CustomVocabularyRepository.deleteTerm(targetWord)
                            editingWord = null
                        }
                    ) {
                        Text(
                            text = "Delete",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    TextButton(
                        onClick = {
                            val trimmed = editedWordValue.trim()
                            if (trimmed.isBlank()) {
                                editError = "Word cannot be blank"
                                return@TextButton
                            }
                            val aliasError = VocabClassifier.aliasError(editAlias)
                            if (aliasError != null) {
                                editError = aliasError
                                return@TextButton
                            }
                            val success = CustomVocabularyRepository.editTerm(
                                oldTerm = targetWord,
                                newTerm = trimmed,
                                spokenAlias = editAlias,
                                classOverride = overrideFor(editClassChoice),
                                useAutoClass = editClassChoice == ClassChoice.AUTO
                            )
                            if (success) {
                                editingWord = null
                            } else {
                                editError = "Could not update word"
                            }
                        }
                    ) {
                        Text(
                            text = "Save",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingWord = null }) {
                    Text(
                        text = "Cancel",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

private enum class ClassChoice {
    AUTO,
    EASY,
    TRICKY
}

private fun overrideFor(choice: ClassChoice): VocabClass? {
    return when (choice) {
        ClassChoice.AUTO -> null
        ClassChoice.EASY -> VocabClass.EASY
        ClassChoice.TRICKY -> VocabClass.TRICKY
    }
}

private fun classChoiceFor(item: com.princeniithompson.rapcha.data.VocabularyItem?): ClassChoice {
    if (item == null || !item.classOverridden) return ClassChoice.AUTO
    return if (item.vocabClass == VocabClass.TRICKY) ClassChoice.TRICKY else ClassChoice.EASY
}

private fun effectiveClass(term: String, choice: ClassChoice): VocabClass {
    return overrideFor(choice) ?: VocabClassifier.classify(term)
}

private fun dictionaryRowNote(item: com.princeniithompson.rapcha.data.VocabularyItem?): String? {
    if (item == null) return null
    val alias = item.spokenAlias.trim()
    if (alias.isNotEmpty()) return "When I say: $alias"
    if (item.vocabClass == VocabClass.TRICKY) {
        return "Tricky · restore will not work until you fill When I say this"
    }
    return null
}

private fun classHint(term: String, choice: ClassChoice, spokenAlias: String): String {
    if (effectiveClass(term, choice) != VocabClass.TRICKY) {
        return "Easy word. It can be sent to Live to help with spelling."
    }
    if (VocabClassifier.aliasParts(spokenAlias).isEmpty()) {
        return "Tricky spelling. Restore will not work until you fill in When I say this."
    }
    return "Tricky spelling. It is not sent to Live. Rapcha writes it when you say the words above."
}

@Composable
private fun WordAndAliasFields(
    spelling: String,
    onSpelling: (String) -> Unit,
    alias: String,
    onAlias: (String) -> Unit,
    spellingTag: String,
    aliasTag: String,
    classChoice: ClassChoice,
    onClassChoice: (ClassChoice) -> Unit
) {
    Text(
        text = "Write it like this",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold
    )
    TextField(
        value = spelling,
        onValueChange = onSpelling,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(spellingTag),
        placeholder = { Text("30frst") },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
    Text(
        text = "Exact spelling in the text box (e.g. 30frst).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "When I say this",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold
    )
    TextField(
        value = alias,
        onValueChange = onAlias,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(aliasTag),
        placeholder = { Text("thirty first") },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
    Text(
        text = "Normal words you will speak. Rapcha swaps them for the spelling above. Needed for tricky spellings Live cannot hear (digits, emails). Separate extra ways with commas.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(12.dp))
    ClassChoiceRow(choice = classChoice, onChoice = onClassChoice)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = classHint(spelling, classChoice, alias),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ClassChoiceRow(
    choice: ClassChoice,
    onChoice: (ClassChoice) -> Unit
) {
    Column {
        Text(
            text = "Spelling",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClassChoice.entries.forEach { option ->
                val selected = option == choice
                TextButton(
                    onClick = { onChoice(option) },
                    modifier = Modifier.testTag("class_choice_${option.name.lowercase()}")
                ) {
                    Text(
                        text = when (option) {
                            ClassChoice.AUTO -> "Auto"
                            ClassChoice.EASY -> "Easy"
                            ClassChoice.TRICKY -> "Tricky"
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}
