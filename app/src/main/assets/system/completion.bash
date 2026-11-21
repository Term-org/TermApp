# Completion for the commands of the Term apps.
# This file is rewritten by the app on every start, so do not edit it.

_term_reply() {
    local word="$1" IFS=$'\n'
    shift
    COMPREPLY=($(compgen -W "$*" -- "$word"))
}

_term_theme_names() {
    local dir file
    for dir in "${TERM_SYSTEM_DIR:-/nonexistent}/themes" "$HOME/.term/themes"; do
        for file in "$dir"/*.properties; do
            [ -f "$file" ] || continue
            file="${file##*/}"
            printf '%s\n' "${file%.properties}"
        done
    done
}

_tn_labels() {
    local file name
    for file in "$PREFIX/etc/apt/sources.list.d"/*.list "$PREFIX/etc/apt/sources.list.d"/*.list.disabled; do
        [ -f "$file" ] || continue
        name="${file##*/}"
        name="${name%.disabled}"
        printf '%s\n' "${name%.list}"
    done
    printf 'tp\ntcp\n'
}

_tn_complete() {
    local line="${COMP_LINE:0:COMP_POINT}"
    local -a words=() found=() extra=()
    local token quoted=0 colon=0 n prev="" base action last actions pre seg

    read -ra words <<<"$line"
    if [[ "$line" =~ [[:space:]]$ ]]; then
        words+=("")
    fi
    n="${#words[@]}"
    token="${words[n-1]}"
    if [ "$n" -ge 2 ]; then
        prev="${words[n-2]}"
    fi
    COMPREPLY=()
    case "$COMP_WORDBREAKS" in
        *:*) colon=1 ;;
    esac

    # tn -c ... (repositories)
    if [ "$n" -ge 2 ] && { [ "${words[1]}" = "-c" ] || [ "${words[1]}" = "community" ]; }; then
        if [ "$n" -eq 3 ]; then
            _term_reply "$token" add list remove
            return 0
        fi
        case "${words[2]:-}" in
            add)
                case "$prev" in
                    -k|--key)
                        compopt -o filenames 2>/dev/null
                        mapfile -t COMPREPLY < <(compgen -f -- "$token")
                        ;;
                    -l|-label|--label|-s|--suite|-m|--component)
                        ;;
                    *)
                        if [[ "$token" == -* ]]; then
                            _term_reply "$token" -l -label -k --key -s --suite -m --component -y
                        fi
                        ;;
                esac
                ;;
            remove)
                if [ "$n" -eq 4 ]; then
                    _term_reply "$token" $(_tn_labels)
                fi
                ;;
        esac
        return 0
    fi

    case "$token" in
        \"*|\'*)
            quoted=1
            token="${token:1}"
            ;;
    esac

    # <packages>:<action>
    if [[ "$token" == *:* ]]; then
        base="${token%:*}"
        action="${token##*:}"
        last="${base##*[;,]}"
        if [ "$last" = "gp" ]; then
            actions="update upgrade list clean autoremove"
        else
            actions="install remove purge details upgrade freeze unfreeze files search activate non-active"
        fi
        _term_reply "$action" $actions
        if [ "$quoted" -eq 1 ] || [ "$colon" -eq 0 ]; then
            COMPREPLY=("${COMPREPLY[@]/#/$base:}")
        fi
        return 0
    fi

    # package names (the last name of a list that uses ; or ,)
    seg="${token##*[;,]}"
    pre="${token%"$seg"}"
    if [ -n "$seg" ]; then
        mapfile -t found < <(apt-cache pkgnames "$seg" 2>/dev/null | head -n 200)
    fi
    if [ -z "$pre" ] && [ "$n" -eq 2 ]; then
        mapfile -t extra < <(compgen -W "gp -c community -y doctor help version" -- "$seg")
        found+=("${extra[@]}")
    fi
    COMPREPLY=("${found[@]/#/$pre}")
    compopt -o nospace 2>/dev/null
    return 0
}

_term_theme_complete() {
    local cur="${COMP_WORDS[COMP_CWORD]}" prev=""
    if [ "$COMP_CWORD" -ge 1 ]; then
        prev="${COMP_WORDS[COMP_CWORD-1]}"
    fi
    if [ "$COMP_CWORD" -eq 1 ]; then
        _term_reply "$cur" list show set reset current help
    elif [ "$COMP_CWORD" -eq 2 ] && { [ "$prev" = "show" ] || [ "$prev" = "set" ]; }; then
        _term_reply "$cur" $(_term_theme_names)
    fi
}

_term_clip_complete() {
    if [ "$COMP_CWORD" -eq 1 ]; then
        _term_reply "${COMP_WORDS[COMP_CWORD]}" set get list clear help
    fi
}

_term_wakelock_complete() {
    if [ "$COMP_CWORD" -eq 1 ]; then
        _term_reply "${COMP_WORDS[COMP_CWORD]}" on off help
    fi
}

_term_shizuku_complete() {
    if [ "$COMP_CWORD" -eq 1 ]; then
        _term_reply "${COMP_WORDS[COMP_CWORD]}" authorize status help
    fi
}

_term_vnc_complete() {
    local cur="${COMP_WORDS[COMP_CWORD]}"
    if [ "$COMP_CWORD" -eq 1 ]; then
        _term_reply "$cur" start open stop status help
    elif [[ "$cur" == -* ]]; then
        _term_reply "$cur" -d --display -g --geometry -H --host -p --port -h --help
    fi
}

_term_notify_complete() {
    local cur="${COMP_WORDS[COMP_CWORD]}"
    if [[ "$cur" == -* ]]; then
        _term_reply "$cur" -t --title -c --content -i --id --ongoing --remove -h --help
    fi
}

_term_open_complete() {
    local cur="${COMP_WORDS[COMP_CWORD]}"
    if [[ "$cur" == -* ]]; then
        _term_reply "$cur" -t --type -h --help
    fi
}

_term_share_complete() {
    local cur="${COMP_WORDS[COMP_CWORD]}"
    if [[ "$cur" == -* ]]; then
        _term_reply "$cur" -t --title -m --mime -h --help
    fi
}

_term_api_complete() {
    local cur="${COMP_WORDS[COMP_CWORD]}"
    if [ "$COMP_CWORD" -eq 1 ]; then
        _term_reply "$cur" status shizuku help
    elif [ "$COMP_CWORD" -eq 2 ] && [ "${COMP_WORDS[1]}" = "shizuku" ]; then
        _term_reply "$cur" status authorize exec
    fi
}

complete -F _tn_complete tn
complete -F _term_api_complete term-api
complete -F _term_theme_complete term-theme
complete -F _term_clip_complete term-clip
complete -F _term_wakelock_complete term-wakelock
complete -F _term_shizuku_complete term-shizuku
complete -F _term_vnc_complete terminal-vnc
complete -F _term_notify_complete term-notify
complete -o default -F _term_open_complete term-open
complete -o default -F _term_share_complete term-share