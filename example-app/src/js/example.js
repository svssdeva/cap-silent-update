import { SilentUpdate } from '@beyondcodekarma/cap-silent-update';

window.testEcho = () => {
    const inputValue = document.getElementById("echoInput").value;
    SilentUpdate.echo({ value: inputValue })
}
